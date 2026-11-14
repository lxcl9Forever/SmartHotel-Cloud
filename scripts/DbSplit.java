import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.*;
/** One-instance, two-schema migration. Run with applications stopped and a private .env. */
class DbSplit {
  static final Set<String> USER_TABLES=Set.of("sys_role","sys_user","employee","member_level","customer","customer_saved_guest");
  static final Set<String> HOTEL_TABLES=Set.of("hotel_info","room_type","room_type_image","room_price");
  static final Set<String> INVENTORY_TABLES=Set.of("room_inventory","inventory_operation","inventory_cache_sync","inventory_reservation","inventory_tcc_attempt");
  static final String AUDIT="operation_log";
  static final String USER_AUDIT="module IN ('AUTH','USER','EMPLOYEE')";
  static final String BUSINESS_AUDIT="module NOT IN ('AUTH','USER','EMPLOYEE') OR module IS NULL";
  final Path root, output;
  final Map<String,String> env=new LinkedHashMap<>();
  final String host, source="smart_hotel_cloud";
  final int port;
  final Properties report=new Properties();
  final List<String> createdSchemas=new ArrayList<>(),createdUsers=new ArrayList<>();
  Connection admin;
  record Credentials(String user,String password) {
  }
  record Fingerprint(long count,String sha256) {
  }
  DbSplit(Path root,String host,int port)throws Exception {
    this.root=root;
    this.host=host;
    this.port=port;
    for(String line:Files.readAllLines(root.resolve(".env"),StandardCharsets.UTF_8)) {
      int at=line.indexOf('=');
      if(at>0&&!line.startsWith("#"))env.put(line.substring(0,at),line.substring(at+1));
    }
    if(!env.containsKey("MYSQL_ROOT_PASSWORD"))throw new IllegalStateException("MYSQL_ROOT_PASSWORD missing in project .env");
    String stamp=LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))+"-"+UUID.randomUUID().toString().substring(0,6);
    output=root.resolve(".run/db-backups/"+stamp);
    Files.createDirectories(output);
    admin=connect("mysql","root",env.get("MYSQL_ROOT_PASSWORD"));
  }
  Connection connect(String schema,String user,String password)throws SQLException {
    ident(schema);
    Connection connection=DriverManager.getConnection("jdbc:mysql://"+host+":"+port+"/"+schema+
    "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true&tinyInt1isBit=false",user,password);
    try(Statement st=connection.createStatement()) {
      st.execute("SET time_zone='+08:00'");
    }
    return connection;
  }
  static String ident(String value) {
    if(!value.matches("[A-Za-z0-9_]+"))throw new IllegalArgumentException("Invalid SQL identifier");
    return "`"+value+"`";
  }
  static String literal(String value) {
    return "'"+value.replace("\\","\\\\").replace("'","''")+"'";
  }
  static String full(String schema,String table) {
    return ident(schema)+"."+ident(table);
  }
  void execute(String sql)throws SQLException {
    try(Statement st=admin.createStatement()) {
      st.execute(sql);
    }
  }
  long number(String sql)throws SQLException {
    try(Statement st=admin.createStatement();ResultSet rs=st.executeQuery(sql)) {
      rs.next();
      return rs.getLong(1);
    }
  }
  List<String> tables(String schema)throws SQLException {
    var names=new ArrayList<String>();
    try(Statement st=admin.createStatement();ResultSet rs=st.executeQuery("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA="+literal(schema)+" AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME")) {
      while(rs.next())names.add(rs.getString(1));
    }
    return names;
  }
  String ddl(String schema,String table)throws SQLException {
    try(Statement st=admin.createStatement();ResultSet rs=st.executeQuery("SHOW CREATE TABLE "+full(schema,table))) {
      rs.next();
      return rs.getString(2);
    }
  }
  String order(String schema,String table)throws SQLException {
    var keys=new ArrayList<String>();
    try(Statement st=admin.createStatement();ResultSet rs=st.executeQuery("SELECT COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA="+literal(schema)+" AND TABLE_NAME="+literal(table)+" AND CONSTRAINT_NAME='PRIMARY' ORDER BY ORDINAL_POSITION")) {
      while(rs.next())keys.add(ident(rs.getString(1)));
    }
    if(keys.isEmpty())throw new IllegalStateException("A table has no stable primary key: "+table);
    return String.join(",",keys);
  }
  String select(String schema,String table,String filter)throws SQLException {
    return "SELECT * FROM "+full(schema,table)+(filter==null?"":" WHERE "+filter)+" ORDER BY "+order(schema,table);
  }
  Fingerprint fingerprint(String schema,String table,String filter)throws Exception {
    MessageDigest digest=MessageDigest.getInstance("SHA-256");
    long count=0;
    try(Statement st=admin.createStatement();ResultSet rs=st.executeQuery(select(schema,table,filter))) {
      while(rs.next()) {
        count++;
        for(int i=1;i<=rs.getMetaData().getColumnCount();i++) {
          byte[] value=rs.getBytes(i);
          if(value==null)digest.update(new byte[] {
            -1,-1,-1,-1
          }
          );
          else {
            digest.update(java.nio.ByteBuffer.allocate(4).putInt(value.length).array());
            digest.update(value);
          }
        }
      }
    }
    return new Fingerprint(count,HexFormat.of().formatHex(digest.digest()));
  }
  void compare(String left,String right,String table,String filter)throws Exception {
    Fingerprint before=fingerprint(left,table,filter),after=fingerprint(right,table,null);
    if(!before.equals(after))throw new IllegalStateException("Row checksum mismatch: "+table);
    report.setProperty(right+"."+table+".count",Long.toString(after.count()));
    report.setProperty(right+"."+table+".sha256",after.sha256());
  }
  void stopRequired()throws Exception {
    for(int p:List.of(8080,8081,8082,8083,8084))try(Socket socket=new Socket()) {
      try {
        socket.connect(new InetSocketAddress("127.0.0.1",p),500);
        throw new IllegalStateException("Stop project Java services before database operations; port "+p+" is active");
      }
      catch(ConnectException|SocketTimeoutException expected) {
      }
    }
  }
  void rejectUnsupportedObjects(String schema)throws SQLException {
    for(String query:List.of("SELECT COUNT(*) FROM information_schema.VIEWS WHERE TABLE_SCHEMA=", "SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=", "SELECT COUNT(*) FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA=", "SELECT COUNT(*) FROM information_schema.EVENTS WHERE EVENT_SCHEMA=")) {
      if(number(query+literal(schema))>0)throw new IllegalStateException("Schema contains views/triggers/routines/events; use a full vendor backup procedure instead");
    }
  }
  Path backup(String schema)throws Exception {
    rejectUnsupportedObjects(schema);
    Path path=output.resolve(schema+".sql");
    try(Connection db=connect(schema,"root",env.get("MYSQL_ROOT_PASSWORD"));BufferedWriter file=Files.newBufferedWriter(path,StandardCharsets.UTF_8)) {
      db.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      db.setAutoCommit(false);
      try(Statement st=db.createStatement()) {
        st.execute("START TRANSACTION WITH CONSISTENT SNAPSHOT");
      }
      file.write("SET NAMES utf8mb4;\nSET time_zone='+08:00';\nSET FOREIGN_KEY_CHECKS=0;\n");
      for(String table:tables(schema)) {
        file.write(ddl(schema,table)+";\n");
        try(Statement st=db.createStatement();ResultSet rs=st.executeQuery(select(schema,table,null))) {
          ResultSetMetaData meta=rs.getMetaData();
          var columns=new ArrayList<String>();
          for(int i=1;i<=meta.getColumnCount();i++)columns.add(ident(meta.getColumnName(i)));
          while(rs.next()) {
            var values=new ArrayList<String>();
            for(int i=1;i<=meta.getColumnCount();i++) {
              byte[] value=rs.getBytes(i);
              if(value==null)values.add("NULL");
              else {
                String hex="X'"+HexFormat.of().formatHex(value)+"'";
                int type=meta.getColumnType(i);
                values.add(Set.of(Types.BINARY,Types.VARBINARY,Types.LONGVARBINARY,Types.BLOB,Types.BIT).contains(type)?hex:"CONVERT("+hex+" USING utf8mb4)");
              }
            }
            file.write("INSERT INTO "+ident(table)+" ("+String.join(",",columns)+") VALUES ("+String.join(",",values)+");\n");
          }
        }
        Fingerprint fp=fingerprint(schema,table,null);
        report.setProperty("backup."+schema+"."+table+".count",Long.toString(fp.count()));
        report.setProperty("backup."+schema+"."+table+".sha256",fp.sha256());
      }
      file.write("SET FOREIGN_KEY_CHECKS=1;\n");
      db.commit();
    }
    report.setProperty("backup."+schema+".fileSha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
    saveReport("BACKUP_COMPLETE");
    return path;
  }
  void createSchema(String schema)throws Exception {
    if(number("SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME="+literal(schema))!=0)throw new IllegalStateException("Destination schema already exists; refusing to overwrite: "+schema);
    String charset="utf8mb4",collation="utf8mb4_0900_ai_ci";
    try(Statement st=admin.createStatement();ResultSet rs=st.executeQuery("SELECT DEFAULT_CHARACTER_SET_NAME,DEFAULT_COLLATION_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME="+literal(source))) {
      if(rs.next()) {
        charset=rs.getString(1);
        collation=rs.getString(2);
      }
    }
    execute("CREATE DATABASE "+ident(schema)+" CHARACTER SET "+ident(charset)+" COLLATE "+ident(collation));
    createdSchemas.add(schema);
  }
  void restore(Path backup,String schema)throws Exception {
    try(Connection db=connect(schema,"root",env.get("MYSQL_ROOT_PASSWORD"));BufferedReader reader=Files.newBufferedReader(backup,StandardCharsets.UTF_8);Statement st=db.createStatement()) {
      StringBuilder sql=new StringBuilder();
      String line;
      while((line=reader.readLine())!=null) {
        sql.append(line).append('\n');
        if(line.endsWith(";")) {
          st.execute(sql.toString());
          sql.setLength(0);
        }
      }
      if(!sql.toString().isBlank())throw new IllegalStateException("Incomplete SQL backup");
    }
  }
  String ownedDdl(String from,String table,Set<String> owned)throws Exception {
    String original=ddl(from,table);
    var lines=new ArrayList<String>();
    Pattern reference=Pattern.compile("REFERENCES\\s+(?:`[^`]+`\\.)?`([^`]+)`");
    for(String line:original.split("\\n")) {
      Matcher matcher=reference.matcher(line);
      if(matcher.find() && !owned.contains(matcher.group(1))) {
        if(!line.contains("FOREIGN KEY"))throw new IllegalStateException("Unsupported multi-line constraint");
        continue;
      }
      lines.add(line);
    }
    return String.join("\n",lines).replaceAll(",\\n\\)","\n)");
  }
  String qualify(String sql,String schema,String table) {
    return sql.replaceFirst("CREATE TABLE `"+table+"`","CREATE TABLE "+full(schema,table))
    .replaceAll("REFERENCES\\s+(?:`[^`]+`\\.)?`([^`]+)`", "REFERENCES "+ident(schema)+".`$1`");
  }
  void copyRows(String from,String to,String table,String filter,boolean mergeAudit)throws Exception {
    try(Statement st=admin.createStatement();ResultSet rs=st.executeQuery(select(from,table,filter))) {
      var meta=rs.getMetaData();
      var columns=new ArrayList<String>();
      for(int i=1;i<=meta.getColumnCount();i++)columns.add(ident(meta.getColumnName(i)));
      String sql="INSERT INTO "+full(to,table)+" ("+String.join(",",columns)+") VALUES ("+String.join(",",Collections.nCopies(columns.size(),"?"))+")";
      try(PreparedStatement insert=admin.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS)) {
        while(rs.next()) {
          boolean remap=mergeAudit && number("SELECT COUNT(*) FROM "+full(to,table)+" WHERE id="+rs.getLong("id"))!=0;
          for(int i=1;i<=columns.size();i++)insert.setObject(i,remap && meta.getColumnName(i).equals("id")?null:rs.getObject(i));
          insert.executeUpdate();
          if(remap)try(ResultSet keys=insert.getGeneratedKeys()) {
            keys.next();
            report.setProperty("rollback.auditRemap."+rs.getLong("id"),Long.toString(keys.getLong(1)));
          }
        }
      }
    }
  }
  void checkForeignKeys(String schema)throws Exception {
    if(number("SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA="+literal(schema)+" AND REFERENCED_TABLE_SCHEMA IS NOT NULL AND REFERENCED_TABLE_SCHEMA<>TABLE_SCHEMA")!=0)throw new IllegalStateException("Cross-schema FK remains in "+schema);
    String sql="SELECT TABLE_NAME,COLUMN_NAME,REFERENCED_TABLE_NAME,REFERENCED_COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA="+literal(schema)+" AND REFERENCED_TABLE_NAME IS NOT NULL";
    var checks=new ArrayList<String[]>();
    try(Statement st=admin.createStatement();ResultSet rs=st.executeQuery(sql)) {
      while(rs.next())checks.add(new String[] {
        rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4)
      }
      );
    }
    for(String[] c:checks)if(number("SELECT COUNT(*) FROM "+full(schema,c[0])+" c LEFT JOIN "+full(schema,c[2])+" p ON c."+ident(c[1])+"=p."+ident(c[3])+" WHERE c."+ident(c[1])+" IS NOT NULL AND p."+ident(c[3])+" IS NULL")!=0)throw new IllegalStateException("Orphan foreign key: "+c[0]+"."+c[1]);
  }
  void checkReferences(String user,String business)throws Exception {
    for(String[] item:List.of(new String[] {
      "booking_order","customer_id","customer","customer_id"
    }
    ,
    new String[] {
      "customer_coupon","customer_id","customer","customer_id"
    }
    ,new String[] {
      "customer_checkin_state","customer_id","customer","customer_id"
    }
    ,
    new String[] {
      "customer_checkin_record","customer_id","customer","customer_id"
    }
    ,new String[] {
      "order_review","customer_id","customer","customer_id"
    }
    ,
    new String[] {
      "refund_record","user_id","sys_user","user_id"
    }
    ,new String[] {
      "room_assignment","assigned_by","sys_user","user_id"
    }
    ,
    new String[] {
      "check_in_record","handled_by","sys_user","user_id"
    }
    ,new String[] {
      "check_out_record","handled_by","sys_user","user_id"
    }
    ,
    new String[] {
      "room_status_log","operator_user_id","sys_user","user_id"
    }
    )) {
      long orphans=number("SELECT COUNT(*) FROM "+full(business,item[0])+" b LEFT JOIN "+full(user,item[2])+" u ON b."+ident(item[1])+"=u."+ident(item[3])+" WHERE b."+ident(item[1])+" IS NOT NULL AND u."+ident(item[3])+" IS NULL");
      if(orphans!=0)throw new IllegalStateException("User ID mapping mismatch: "+item[0]+"."+item[1]);
    }
    checkForeignKeys(user);
    checkForeignKeys(business);
  }
  void copySplit(String snapshot,String user,String business)throws Exception {
    createSchema(user);
    createSchema(business);
    var ownedUser=new HashSet<>(USER_TABLES);
    ownedUser.add(AUDIT);
    var ownedBusiness=new HashSet<>(tables(snapshot));
    ownedBusiness.removeAll(USER_TABLES);
    execute("SET FOREIGN_KEY_CHECKS=0");
    try {
      for(String table:tables(snapshot)) {
        if(ownedUser.contains(table))execute(qualify(ownedDdl(snapshot,table,ownedUser),user,table));
        if(ownedBusiness.contains(table))execute(qualify(ownedDdl(snapshot,table,ownedBusiness),business,table));
        if(table.equals(AUDIT)) {
          copyRows(snapshot,user,table,USER_AUDIT,false);
          copyRows(snapshot,business,table,BUSINESS_AUDIT,false);
          compare(snapshot,user,table,USER_AUDIT);
          compare(snapshot,business,table,BUSINESS_AUDIT);
        }
        else {
          String to=USER_TABLES.contains(table)?user:business;
          copyRows(snapshot,to,table,null,false);
          compare(snapshot,to,table,null);
        }
      }
    }
    finally {
      execute("SET FOREIGN_KEY_CHECKS=1");
    }
    checkReferences(user,business);
    report.setProperty("split.checksums","all tables and audit partitions match");
  }
  Credentials createPrincipal(String username,String schema,Set<String> owned)throws Exception {
    if(number("SELECT COUNT(*) FROM mysql.user WHERE User="+literal(username))!=0)throw new IllegalStateException("Database account exists; refusing to alter: "+username);
    byte[] random=new byte[36];
    new SecureRandom().nextBytes(random);
    String password=Base64.getEncoder().encodeToString(random);
    execute("CREATE USER "+literal(username)+"@'%' IDENTIFIED BY "+literal(password));
    createdUsers.add(username);
    for(String table:owned)execute("GRANT SELECT,INSERT,UPDATE,DELETE ON "+full(schema,table)+" TO "+literal(username)+"@'%'");
    return new Credentials(username,password);
  }
  void denied(Connection db,String sql)throws SQLException {
    try(Statement st=db.createStatement()) {
      st.execute(sql);
      throw new IllegalStateException("Unexpected database permission: "+sql);
    }
    catch(SQLException error) {
      if(!Set.of(1044,1142).contains(error.getErrorCode()))throw error;
    }
  }
  void checkPrivileges(String user,String business,Credentials u,Credentials b)throws Exception {
    try(Connection db=connect(user,u.user(),u.password());Statement st=db.createStatement()) {
      st.executeQuery("SELECT COUNT(*) FROM "+full(user,"sys_user")).close();
      denied(db,"SELECT * FROM "+full(business,"booking_order")+" LIMIT 0");
      denied(db,"SELECT * FROM "+full(source,"sys_user")+" LIMIT 0");
      denied(db,"CREATE TABLE "+full(user,"_privilege_probe")+" (id INT)");
    }
    try(Connection db=connect(business,b.user(),b.password());Statement st=db.createStatement()) {
      st.executeQuery("SELECT COUNT(*) FROM "+full(business,"booking_order")).close();
      denied(db,"SELECT * FROM "+full(user,"sys_user")+" LIMIT 0");
      denied(db,"SELECT * FROM "+full(source,"booking_order")+" LIMIT 0");
      denied(db,"CREATE TABLE "+full(business,"_privilege_probe")+" (id INT)");
    }
    report.setProperty("privileges","own-domain DML only; other domain, archive and DDL denied");
  }
  String mergeCurrent(String archive,String user,String business,String target)throws Exception {
    String hotel="split".equals(env.get("HOTEL_DATA_LAYOUT"))?env.get("HOTEL_MYSQL_DATABASE"):business;
    return mergeWithHotel(archive,user,business,hotel,target);
  }
  String mergeWithHotel(String archive,String user,String business,String hotel,String target)throws Exception {
    String inventory="split".equals(env.get("INVENTORY_DATA_LAYOUT"))?env.get("INVENTORY_MYSQL_DATABASE"):business;
    return mergeWithInventory(archive,user,business,hotel,inventory,target);
  }
  String mergeWithInventory(String archive,String user,String business,String hotel,String inventory,String target)throws Exception {
    createSchema(target);
    execute("SET FOREIGN_KEY_CHECKS=0");
    try {
      Set<String> originalTables=new TreeSet<>(tables(archive));
      Set<String> businessTables=new TreeSet<>(tables(business));
      Set<String> mergedTables=new TreeSet<>(originalTables);
      mergedTables.addAll(businessTables);
      mergedTables.addAll(tables(user));
      for(String table:mergedTables) {
        String ddlOwner=INVENTORY_TABLES.contains(table)?inventory:HOTEL_TABLES.contains(table)?hotel:(originalTables.contains(table)?archive:(businessTables.contains(table)?business:user));
        execute(qualify(ddl(ddlOwner,table),target,table));
        if(table.equals(AUDIT)) {
          copyRows(business,target,table,null,false);
          copyRows(user,target,table,null,true);
          long expected=number("SELECT COUNT(*) FROM "+full(user,AUDIT))+number("SELECT COUNT(*) FROM "+full(business,AUDIT));
          if(number("SELECT COUNT(*) FROM "+full(target,AUDIT))!=expected)throw new IllegalStateException("Rollback audit count mismatch");
        }
        else {
          String from=INVENTORY_TABLES.contains(table)?inventory:HOTEL_TABLES.contains(table)?hotel:(USER_TABLES.contains(table)?user:business);
          copyRows(from,target,table,null,false);
          compare(from,target,table,null);
        }
      }
    }
    finally {
      execute("SET FOREIGN_KEY_CHECKS=1");
    }
    var expectedAudit=auditContent(user);
    auditContent(business).forEach((key,count)->expectedAudit.merge(key,count,Integer::sum));
    if(!expectedAudit.equals(auditContent(target)))throw new IllegalStateException("Rollback audit content mismatch");
    checkForeignKeys(target);
    checkReferences(target,target);
    return target;
  }
  Map<String,Integer> auditContent(String schema)throws Exception {
    var hashes=new TreeMap<String,Integer>();
    try(Statement st=admin.createStatement();ResultSet rs=st.executeQuery("SELECT * FROM "+full(schema,AUDIT))) {
      while(rs.next()) {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        for(int i=1;i<=rs.getMetaData().getColumnCount();i++) {
          if(rs.getMetaData().getColumnName(i).equals("id"))continue;
          byte[] value=rs.getBytes(i);
          if(value==null)digest.update(new byte[] {
            -1,-1,-1,-1
          }
          );
          else {
            digest.update(java.nio.ByteBuffer.allocate(4).putInt(value.length).array());
            digest.update(value);
          }
        }
        hashes.merge(HexFormat.of().formatHex(digest.digest()),1,Integer::sum);
      }
    }
    return hashes;
  }
  void saveReport(String phase)throws Exception {
    report.setProperty("phase",phase);
    report.setProperty("source",source);
    report.setProperty("created.schemas",String.join(",",createdSchemas));
    report.setProperty("created.accounts",String.join(",",createdUsers));
    try(Writer out=Files.newBufferedWriter(output.resolve("manifest.properties"),StandardCharsets.UTF_8)) {
      report.store(out,"No passwords are stored in this report");
    }
  }
  void activate(String user,String business,Credentials u,Credentials b,String layout)throws Exception {
    Files.copy(root.resolve(".env"),output.resolve("env-before-cutover.private"));
    var updates=new LinkedHashMap<String,String>();
    updates.put("DB_LAYOUT",layout);
    updates.put("USER_MYSQL_DATABASE",user);
    updates.put("USER_MYSQL_USER",u.user());
    updates.put("USER_MYSQL_PASSWORD",u.password());
    updates.put("BUSINESS_MYSQL_DATABASE",business);
    updates.put("BUSINESS_MYSQL_USER",b.user());
    updates.put("BUSINESS_MYSQL_PASSWORD",b.password());
    if(env.containsKey("HOTEL_MYSQL_USER")) {
      String owner=env.get("HOTEL_MYSQL_USER");
      ident(owner);
      for(String table:HOTEL_TABLES)execute("GRANT SELECT ON "+full(business,table)+" TO "+literal(owner)+"@'%'");
      if("enabled".equals(env.get("HOTEL_WRITE_OWNER"))) {
        execute("GRANT UPDATE ON "+full(business,"room_price")+" TO "+literal(owner)+"@'%'");
        execute("REVOKE INSERT,UPDATE,DELETE ON "+full(business,"room_price")+" FROM "+literal(b.user())+"@'%'");
      }
      if(!env.containsKey("HOTEL_DATA_LAYOUT")) {
        for(String table:List.of("room","room_inventory"))execute("GRANT SELECT ON "+full(business,table)+" TO "+literal(owner)+"@'%'");
      }
      else updates.put("HOTEL_DATA_LAYOUT","rollback-shared");
      updates.put("HOTEL_MYSQL_DATABASE",business);
    }
    if("remote".equals(env.get("INVENTORY_MODE"))) {
      String owner=env.get("INVENTORY_MYSQL_USER");
      ident(owner);
      for(String table:List.of("room_inventory","inventory_operation","inventory_cache_sync","inventory_reservation","inventory_tcc_attempt")) {
        execute("GRANT SELECT,INSERT,UPDATE,DELETE ON "+full(business,table)+" TO "+literal(owner)+"@'%'");
        execute("REVOKE INSERT,UPDATE,DELETE ON "+full(business,table)+" FROM "+literal(b.user())+"@'%'");
      }
      if(!env.containsKey("HOTEL_DATA_LAYOUT"))execute("GRANT SELECT ON "+full(business,"room_type")+" TO "+literal(owner)+"@'%'");
      if(env.containsKey("INVENTORY_DATA_LAYOUT")) {
        for(String table:INVENTORY_TABLES)execute("REVOKE ALL PRIVILEGES ON "+full(business,table)+" FROM "+literal(b.user())+"@'%'");
        updates.put("INVENTORY_DATA_LAYOUT","rollback-shared");
      }
      updates.put("INVENTORY_MYSQL_DATABASE",business);
    }
    if("enabled".equals(env.get("HOTEL_CATALOG_REMOTE"))) {
      for(String table:List.of("room_type","room_price","hotel_info","room_type_image"))
      execute("REVOKE ALL PRIVILEGES ON "+full(business,table)+" FROM "+literal(b.user())+"@'%'");
    }
    List<String> lines=new ArrayList<>();
    for(String line:Files.readAllLines(root.resolve(".env"),StandardCharsets.UTF_8)) {
      int at=line.indexOf('=');
      if(at<0||!updates.containsKey(line.substring(0,at)))lines.add(line);
    }
    updates.forEach((key,value)->lines.add(key+"="+value));
    Path temporary=root.resolve(".env.migration-tmp");
    Files.write(temporary,lines,StandardCharsets.UTF_8);
    Files.move(temporary,root.resolve(".env"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    saveReport("CONFIG_ACTIVATED");
  }
  void freezeArchive()throws Exception {
    String username=env.getOrDefault("MYSQL_USER","smarthotel");
    var patterns=new ArrayList<String>();
    try(Statement st=admin.createStatement();ResultSet rs=st.executeQuery("SHOW GRANTS FOR "+literal(username)+"@'%'")) {
      while(rs.next()) {
        String grant=rs.getString(1);
        if(grant.startsWith("GRANT USAGE ON *.*"))continue;
        Matcher matcher=Pattern.compile(" ON (`([^`]+)`\\.\\*) TO ").matcher(grant);
        if(!matcher.find() || !matcher.group(2).replace("\\","").equals(source))throw new IllegalStateException("Legacy account has unexpected privileges; refusing to modify it");
        patterns.add(matcher.group(1));
      }
    }
    for(String databasePattern:patterns) {
      execute("REVOKE ALL PRIVILEGES ON "+databasePattern+" FROM "+literal(username)+"@'%'");
      execute("GRANT SELECT ON "+databasePattern+" TO "+literal(username)+"@'%'");
    }
    report.setProperty("archive.legacyAccount","read-only");
  }
  Set<String> userOwned() {
    var owned=new HashSet<>(USER_TABLES);
    owned.add(AUDIT);
    return owned;
  }
  Set<String> businessOwned(String schema)throws Exception {
    var owned=new HashSet<>(tables(schema));
    owned.removeAll(USER_TABLES);
    return owned;
  }
  void cleanupRehearsal()throws Exception {
    for(String user:createdUsers) {
      if(!user.startsWith("sh_test_"))throw new IllegalStateException("Not a rehearsal account");
      execute("DROP USER "+literal(user)+"@'%'");
    }
    for(String schema:createdSchemas) {
      if(!schema.startsWith("sh_rehearsal_"))throw new IllegalStateException("Not a rehearsal schema");
      execute("DROP DATABASE "+ident(schema));
    }
  }
  void forward(boolean rehearsal)throws Exception {
    stopRequired();
    if(!rehearsal && env.containsKey("DB_LAYOUT"))throw new IllegalStateException("A database layout is already active; use verify or rollback instead of migrating twice");
    Path file=backup(source);
    String id=UUID.randomUUID().toString().substring(0,8);
    String snapshot="sh_rehearsal_snapshot_"+id;
    createSchema(snapshot);
    restore(file,snapshot);
    for(String table:tables(source))compare(source,snapshot,table,null);
    checkForeignKeys(snapshot);
    saveReport("BACKUP_RESTORE_VERIFIED");
    String user=rehearsal?"sh_rehearsal_user_"+id:"smart_hotel_user";
    String business=rehearsal?"sh_rehearsal_business_"+id:"smart_hotel_business";
    copySplit(snapshot,user,business);
    Credentials u=createPrincipal(rehearsal?"sh_test_u_"+id:"sh_user_app",user,userOwned());
    Credentials b=createPrincipal(rehearsal?"sh_test_b_"+id:"sh_business_app",business,businessOwned(snapshot));
    checkPrivileges(user,business,u,b);
    saveReport("FORWARD_VERIFIED");
    if(rehearsal) {
      execute("INSERT INTO "+full(user,"sys_user")+" (username,password,role_id) SELECT 'db_split_new_after_cutover','synthetic-unused-password',role_id FROM "+full(user,"sys_role")+" WHERE role_code='CUSTOMER'");
      for(String schema:List.of(user,business))execute("INSERT INTO "+full(schema,AUDIT)+" (operation,module,request_url,success) VALUES ('rollback rehearsal','TEST','/synthetic',1)");
      String merged=mergeCurrent(snapshot,user,business,"sh_rehearsal_rollback_"+id);
      if(number("SELECT COUNT(*) FROM "+full(merged,"sys_user")+" WHERE username='db_split_new_after_cutover'")!=1)throw new IllegalStateException("Post-cutover user lost on rollback");
      report.setProperty("rollback.rehearsal","latest data merged; post-cutover account and both audit streams preserved");
      cleanupRehearsal();
      saveReport("REHEARSAL_PASSED");
    }
    else {
      freezeArchive();
      activate(user,business,u,b,"split");
      execute("DROP DATABASE "+ident(snapshot));
    }
  }
  void verify()throws Exception {
    String user=env.get("USER_MYSQL_DATABASE"),business=env.get("BUSINESS_MYSQL_DATABASE");
    if(user==null||business==null)throw new IllegalStateException("No split configuration exists");
    checkReferences(user,business);
    checkPrivileges(user,business,new Credentials(env.get("USER_MYSQL_USER"),env.get("USER_MYSQL_PASSWORD")),new Credentials(env.get("BUSINESS_MYSQL_USER"),env.get("BUSINESS_MYSQL_PASSWORD")));
    try(Connection legacy=connect(source,env.getOrDefault("MYSQL_USER","smarthotel"),env.get("MYSQL_PASSWORD"))) {
      denied(legacy,"UPDATE "+full(source,"sys_user")+" SET user_id=user_id WHERE 1=0");
    }
    try(Statement st=admin.createStatement();ResultSet rs=st.executeQuery("SELECT USER,DB,COUNT(*) FROM information_schema.PROCESSLIST WHERE USER IN ("+literal(env.get("USER_MYSQL_USER"))+","+literal(env.get("BUSINESS_MYSQL_USER"))+") GROUP BY USER,DB")) {
      while(rs.next())System.out.println("Active database connections: "+rs.getString(1)+" -> "+rs.getString(2)+" ("+rs.getInt(3)+")");
    }
    saveReport("ACTIVE_LAYOUT_VERIFIED");
  }
  void resume()throws Exception {
    stopRequired();
    if(env.containsKey("DB_LAYOUT"))throw new IllegalStateException("A layout is already active");
    var candidates=new ArrayList<Path>();
    try(var files=Files.walk(root.resolve(".run/db-backups"))) {
      for(Path file:files.filter(p->p.getFileName().toString().equals("manifest.properties")).toList()) {
        var properties=new Properties();
        try(Reader reader=Files.newBufferedReader(file)) {
          properties.load(reader);
        }
        if(properties.getProperty("phase","").equals("FAILED") && properties.getProperty("created.schemas","").contains("smart_hotel_user")
        && properties.getProperty("created.accounts","").equals("sh_user_app,sh_business_app"))candidates.add(file);
      }
    }
    if(candidates.size()!=1)throw new IllegalStateException("Resume requires exactly one failed, locally recorded canonical migration");
    Path manifest=candidates.get(0);
    var previous=new Properties();
    try(Reader reader=Files.newBufferedReader(manifest)) {
      previous.load(reader);
    }
    Path backup=manifest.getParent().resolve(source+".sql");
    String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(backup)));
    if(!hash.equals(previous.getProperty("backup."+source+".fileSha256")))throw new IllegalStateException("Original backup file checksum mismatch");
    for(String table:tables(source)) {
      Fingerprint fp=fingerprint(source,table,null);
      if(!Long.toString(fp.count()).equals(previous.getProperty("backup."+source+"."+table+".count")) || !fp.sha256().equals(previous.getProperty("backup."+source+"."+table+".sha256")))throw new IllegalStateException("Source changed after failed migration: "+table);
      if(table.equals(AUDIT)) {
        compare(source,"smart_hotel_user",table,USER_AUDIT);
        compare(source,"smart_hotel_business",table,BUSINESS_AUDIT);
      }
      else compare(source,USER_TABLES.contains(table)?"smart_hotel_user":"smart_hotel_business",table,null);
    }
    report.setProperty("recovered.manifest",manifest.toString());
    report.setProperty("recovered.backup",backup.toString());
    var credentials=new ArrayList<Credentials>();
    for(String username:List.of("sh_user_app","sh_business_app")) {
      byte[] random=new byte[36];
      new SecureRandom().nextBytes(random);
      String password=Base64.getEncoder().encodeToString(random);
      execute("ALTER USER "+literal(username)+"@'%' IDENTIFIED BY "+literal(password));
      credentials.add(new Credentials(username,password));
    }
    checkReferences("smart_hotel_user","smart_hotel_business");
    checkPrivileges("smart_hotel_user","smart_hotel_business",credentials.get(0),credentials.get(1));
    freezeArchive();
    activate("smart_hotel_user","smart_hotel_business",credentials.get(0),credentials.get(1),"split");
    for(String schema:previous.getProperty("created.schemas").split(","))if(schema.startsWith("sh_rehearsal_snapshot_"))execute("DROP DATABASE "+ident(schema));
  }
  void rollback()throws Exception {
    stopRequired();
    if(!"split".equals(env.get("DB_LAYOUT")))throw new IllegalStateException("Rollback requires an active split layout");
    String user=env.get("USER_MYSQL_DATABASE"),business=env.get("BUSINESS_MYSQL_DATABASE");
    backup(user);
    backup(business);
    if("split".equals(env.get("HOTEL_DATA_LAYOUT")))backup(env.get("HOTEL_MYSQL_DATABASE"));
    if("split".equals(env.get("INVENTORY_DATA_LAYOUT")))backup(env.get("INVENTORY_MYSQL_DATABASE"));
    String id=LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMddHHmmss"));
    String target=mergeCurrent(source,user,business,"smart_hotel_rollback_"+id);
    Credentials u=createPrincipal("sh_rb_u_"+id,target,userOwned());
    Credentials b=createPrincipal("sh_rb_b_"+id,target,businessOwned(business));
    checkPrivileges(target,target,u,b);
    activate(target,target,u,b,"rollback-shared");
  }
  public static void main(String[] args)throws Exception {
    if(args.length!=4)throw new IllegalArgumentException("Usage: DbSplit.java projectRoot host port inspect|rehearse|migrate|resume|verify|rollback");
    DbSplit tool=new DbSplit(Path.of(args[0]),args[1],Integer.parseInt(args[2]));
    try {
      switch(args[3].toLowerCase(Locale.ROOT)) {
        case "inspect" -> {
          for(String table:tool.tables(tool.source))System.out.println(table+" rows="+tool.number("SELECT COUNT(*) FROM "+full(tool.source,table)));
          tool.saveReport("INSPECTED");
        }
        case "rehearse" -> tool.forward(true);
        case "migrate" -> tool.forward(false);
        case "verify" -> tool.verify();
        case "resume" -> tool.resume();
        case "rollback" -> tool.rollback();
        default -> throw new IllegalArgumentException("Unknown operation");
      }
      System.out.println("Database operation completed. Private backups/report: "+tool.output);
    }
    catch(Exception failure) {
      if(args[3].equalsIgnoreCase("rehearse"))tool.cleanupRehearsal();
      tool.saveReport("FAILED");
      throw failure;
    }
    finally {
      tool.admin.close();
    }
  }
}
