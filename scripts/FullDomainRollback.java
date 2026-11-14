import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.security.MessageDigest;
/** Export latest owners into a NEW four-domain-compatible business schema; never rewinds live databases. */
class FullDomainRollback {
  static String fields(DbSplit t,String schema,String table,boolean audit)throws Exception {
    var list=new ArrayList<String>();
    try(var st=t.admin.createStatement();var rs=st.executeQuery("SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA="+DbSplit.literal(schema)+" AND TABLE_NAME="+DbSplit.literal(table)+" ORDER BY ORDINAL_POSITION")) {
      while(rs.next()) {
        String name=rs.getString(1);
        if(!audit||!name.equals("id"))list.add(DbSplit.ident(name));
      }
    }
    return String.join(",",list);
  }
  static String digest(DbSplit t,String query)throws Exception {
    var hash=MessageDigest.getInstance("SHA-256");
    long count=0;
    try(var st=t.admin.createStatement();var rs=st.executeQuery(query)) {
      while(rs.next()) {
        count++;
        for(int i=1;i<=rs.getMetaData().getColumnCount();i++) {
          byte[] v=rs.getBytes(i);
          hash.update(java.nio.ByteBuffer.allocate(4).putInt(v==null?-1:v.length).array());
          if(v!=null)hash.update(v);
        }
      }
    }
    return count+":"+HexFormat.of().formatHex(hash.digest());
  }
  static void export(DbSplit t)throws Exception {
    if(!"split".equals(t.env.get("FULL_DOMAIN_LAYOUT")))throw new IllegalStateException("Nine-domain layout is required");
    String archive=t.env.get("ORDER_LEGACY_ARCHIVE");
    var domains=new LinkedHashMap<String,String>();
    for(String domain:FullDomainSplit.OWNED.keySet())domains.put(domain,t.env.get(domain.toUpperCase(Locale.ROOT)+"_MYSQL_DATABASE"));
    for(var d:domains.entrySet()) {
      String db=d.getValue();
      if(t.number("SELECT COUNT(*) FROM "+DbSplit.full(db,"domain_outbox")+" WHERE status<>'DELIVERED'")!=0||t.number("SELECT COUNT(*) FROM "+DbSplit.full(db,"domain_workflow")+" WHERE status IN ('INTENT','COMMITTED','ABORTING')")!=0)throw new IllegalStateException("Resolve pending workflows and event deliveries before rollback: "+d.getKey());
    }
    String orders=domains.get("order");
    if(t.number("SELECT COUNT(*) FROM "+DbSplit.full(orders,"inventory_tcc_journal")+" WHERE state='PENDING'")!=0||t.number("SELECT COUNT(*) FROM "+DbSplit.full(orders,"marketing_tcc_journal")+" WHERE done=0")!=0||t.number("SELECT COUNT(*) FROM "+DbSplit.full(orders,"order_workflow")+" WHERE status='ACTIVE'")!=0)throw new IllegalStateException("Resolve authoritative pending decisions before rollback");
    Files.copy(t.root.resolve(".env"),t.output.resolve("env-before-rollback-export.private"));
    String suffix=UUID.randomUUID().toString().substring(0,8);
    for(String db:domains.values())FullDomainSplit.restoreProof(t,db,suffix+db.substring(db.lastIndexOf('_')));
    for(String domain:List.of("USER","HOTEL","INVENTORY"))FullDomainSplit.restoreProof(t,t.env.get(domain+"_MYSQL_DATABASE"),suffix+domain.toLowerCase(Locale.ROOT));
    String target="smart_hotel_business_rb_"+suffix;
    t.createSchema(target);
    var ownership=new HashMap<String,String>();
    domains.forEach((domain,db)->FullDomainSplit.OWNED.get(domain).forEach(table->ownership.put(table,db)));
    DbSplit.HOTEL_TABLES.forEach(table->ownership.put(table,t.env.get("HOTEL_MYSQL_DATABASE")));
    DbSplit.INVENTORY_TABLES.forEach(table->ownership.put(table,t.env.get("INVENTORY_MYSQL_DATABASE")));
    t.execute("SET FOREIGN_KEY_CHECKS=0");
    try {
      for(String table:t.tables(archive)) {
        t.execute(t.qualify(t.ddl(archive,table),target,table));
        if(table.equals("operation_log")) {
          t.copyRows(orders,target,table,null,false);
          String cols=fields(t,target,table,true);
          String match=String.join(" AND ",Arrays.stream(cols.split(",")).map(c->"a."+c+" <=> l."+c).toList());
          for(var entry:domains.entrySet())if(!entry.getKey().equals("order")) {
            String owner=entry.getValue();
            String filter="NOT EXISTS (SELECT 1 FROM "+DbSplit.full(archive,table)+" a WHERE "+match+")";
            long expected=t.number("SELECT COUNT(*) FROM "+DbSplit.full(owner,table)+" l WHERE "+filter),before=t.number("SELECT COUNT(*) FROM "+DbSplit.full(target,table));
            t.execute("INSERT INTO "+DbSplit.full(target,table)+"("+cols+") SELECT "+cols+" FROM "+DbSplit.full(owner,table)+" l WHERE "+filter);
            if(t.number("SELECT COUNT(*) FROM "+DbSplit.full(target,table))-before!=expected)throw new IllegalStateException("Audit merge mismatch");
          }
        }
        else {
          String owner=ownership.get(table);
          if(owner==null)throw new IllegalStateException("Unknown rollback table owner: "+table);
          String cols=fields(t,target,table,false);
          t.execute("INSERT INTO "+DbSplit.full(target,table)+"("+cols+") SELECT "+cols+" FROM "+DbSplit.full(owner,table));
          String left=digest(t,"SELECT "+cols+" FROM "+DbSplit.full(owner,table)+" ORDER BY "+t.order(target,table));
          String right=digest(t,"SELECT "+cols+" FROM "+DbSplit.full(target,table)+" ORDER BY "+t.order(target,table));
          if(!left.equals(right))throw new IllegalStateException("Rollback row checksum mismatch: "+table);
          t.report.setProperty("rollback."+table,right);
        }
      }
    }
    finally {
      t.execute("SET FOREIGN_KEY_CHECKS=1");
    }
    t.checkForeignKeys(target);
    t.checkReferences(t.env.get("USER_MYSQL_DATABASE"),target);
    var creds=t.createPrincipal("sh_rb_business_"+suffix,target,new HashSet<>(t.tables(target)));
    var lines=new ArrayList<String>();
    for(String line:Files.readAllLines(t.root.resolve(".env"),StandardCharsets.UTF_8)) {
      String key=line.contains("=")?line.substring(0,line.indexOf('=')):"";
      if(key.startsWith("BUSINESS_MYSQL_")||key.equals("FULL_DOMAIN_LAYOUT")||key.equals("ORDER_LEGACY_ARCHIVE")||domains.keySet().stream().anyMatch(d->key.startsWith(d.toUpperCase(Locale.ROOT)+"_MYSQL_")))continue;
      lines.add(line);
    }
    lines.add("BUSINESS_MYSQL_DATABASE="+target);
    lines.add("BUSINESS_MYSQL_USER="+creds.user());
    lines.add("BUSINESS_MYSQL_PASSWORD="+creds.password());
    Files.write(t.output.resolve("four-domain-rollback.env.private"),lines,StandardCharsets.UTF_8);
    FullDomainSplit.restoreProof(t,target,suffix+"rollback");
    t.report.setProperty("rollback.target",target);
    t.report.setProperty("rollback.activation","Export only. Current .env and nine-domain schemas unchanged. Use archived four-domain application with the exported private environment.");
    t.saveReport("FULL_DOMAIN_ROLLBACK_EXPORT_VERIFIED");
    System.out.println("Latest-data rollback export verified. Current configuration unchanged. Private output: "+t.output);
  }
}
