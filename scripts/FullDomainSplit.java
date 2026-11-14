import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
/** Stopped-writer migration from the verified four-domain layout to nine bounded data domains. */
class FullDomainSplit {
  static final Map<String,Set<String>> OWNED=new LinkedHashMap<>();
  static {
    OWNED.put("order",Set.of("booking_order","booking_guest","booking_request","inventory_tcc_journal"));
    OWNED.put("review",Set.of("order_review"));
    OWNED.put("marketing",Set.of("promotion","daily_deal","daily_checkin_reward","customer_checkin_state","customer_coupon","customer_checkin_record"));
    OWNED.put("payment",Set.of("payment","refund_record","refund_payment_record"));
    OWNED.put("stay",Set.of("room","room_assignment","room_status_log","check_in_record","check_out_record"));
    OWNED.put("statistics",Set.of("hotel_daily_statistics"));
  }
  static final Map<String,Map<String,String>> PROJECTIONS=Map.of(
  "order",Map.of("payment:payment","payment_snapshot","payment:refund_record","refund_snapshot","stay:room","room_snapshot"),
  "marketing",Map.of("order:booking_order","order_snapshot"),
  "payment",Map.of("order:booking_order","order_snapshot","stay:stay_charge","stay_charge_snapshot"),
  "stay",Map.of("order:booking_order","order_snapshot","order:booking_guest","guest_snapshot"),
  "statistics",Map.of("order:booking_order","order_snapshot","payment:payment","payment_snapshot","stay:check_in_record","check_in_record_snapshot","stay:check_out_record","check_out_record_snapshot","stay:room_assignment","room_assignment_snapshot"));
  static final Set<String> EVENT_TABLES=Set.of("event_revision","domain_outbox","domain_inbox","projection_revision","domain_workflow");
  static void stopped(DbSplit t)throws Exception {
    t.stopRequired();
    for(int port:List.of(8085,8086,8087,8089,8090))try(var socket=new java.net.Socket()) {
      try {
        socket.connect(new java.net.InetSocketAddress("127.0.0.1",port),300);
        throw new IllegalStateException("Stop application writers before migration: "+port);
      }
      catch(java.net.ConnectException|java.net.SocketTimeoutException expected) {
      }
    }
  }
  static String schema(Map<String,String> domains,String role) {
    return domains.get(role);
  }
  static void cloneTable(DbSplit t,String from,String to,String table,Set<String> owned)throws Exception {
    t.execute(t.qualify(t.ownedDdl(from,table,owned),to,table));
    t.copyRows(from,to,table,null,false);
    t.compare(from,to,table,null);
  }
  static void projection(DbSplit t,String from,String sourceTable,String to,String target)throws Exception {
    String ddl=t.ownedDdl(from,sourceTable,Set.of());
    ddl=ddl.replaceFirst("CREATE TABLE `"+sourceTable+"`","CREATE TABLE "+DbSplit.full(to,target));
    t.execute(ddl);
    t.execute("INSERT INTO "+DbSplit.full(to,target)+" SELECT * FROM "+DbSplit.full(from,sourceTable));
    if(t.number("SELECT COUNT(*) FROM "+DbSplit.full(from,sourceTable))!=t.number("SELECT COUNT(*) FROM "+DbSplit.full(to,target)))throw new IllegalStateException("Projection bootstrap count mismatch: "+target);
  }
  static void metadata(DbSplit t,String domain,String target)throws Exception {
    t.execute("USE "+DbSplit.ident(target));
    for(String statement:Files.readString(t.root.resolve("scripts/domain-events-schema.sql")).split(";")) {
      statement=statement.trim();
      if(statement.isEmpty())continue;
      String table=statement.split("[ (]+",8)[5];
      boolean apply=EVENT_TABLES.contains(table)||domain.equals("order")&&Set.of("order_workflow","marketing_tcc_journal").contains(table)||domain.equals("marketing")&&table.equals("marketing_reservation")||domain.equals("stay")&&table.equals("stay_charge");
      if(apply)t.execute(statement);
    }
  }
  static Map<String,String> build(DbSplit t,String source,String suffix)throws Exception {
    Map<String,String> domains=new LinkedHashMap<>();
    for(var entry:OWNED.entrySet()) {
      String domain=entry.getKey(),target="smart_hotel_"+domain+suffix;
      domains.put(domain,target);
      if(t.number("SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME="+DbSplit.literal(target))!=0)throw new IllegalStateException("Target already exists; inspect private manifest before retrying: "+target);
      t.createSchema(target);
      t.execute("SET FOREIGN_KEY_CHECKS=0");
      try {
        for(String table:entry.getValue())cloneTable(t,source,target,table,entry.getValue());
        cloneTable(t,source,target,"operation_log",Set.of("operation_log"));
      }
      finally {
        t.execute("SET FOREIGN_KEY_CHECKS=1");
      }
      if(!domain.equals("order"))t.execute("DELETE FROM "+DbSplit.full(target,"operation_log"));
      metadata(t,domain,target);
      t.checkForeignKeys(target);
    }
    String o=domains.get("order"),m=domains.get("marketing"),p=domains.get("payment"),review=domains.get("review");
    t.execute("ALTER TABLE "+DbSplit.full(o,"booking_order")+" ADD COLUMN active_workflow_id VARCHAR(36) NULL, ADD COLUMN marketing_operation_id VARCHAR(36) NULL, ADD UNIQUE KEY uk_order_marketing_operation(marketing_operation_id)");
    t.execute("ALTER TABLE "+DbSplit.full(m,"customer_coupon")+" ADD COLUMN reservation_id VARCHAR(36) NULL, ADD KEY idx_coupon_reservation(reservation_id)");
    t.execute("ALTER TABLE "+DbSplit.full(p,"payment")+" ADD COLUMN origin_operation_id VARCHAR(64) NULL, ADD UNIQUE KEY uk_payment_origin_operation(origin_operation_id)");
    t.execute("ALTER TABLE "+DbSplit.full(review,"order_review")+" ADD COLUMN order_no VARCHAR(64) NULL, ADD COLUMN room_type_id BIGINT NULL, ADD KEY idx_review_room_status(room_type_id,status)");
    t.execute("UPDATE "+DbSplit.full(review,"order_review")+" r JOIN "+DbSplit.full(o,"booking_order")+" o ON r.order_id=o.order_id SET r.order_no=o.order_no,r.room_type_id=o.room_type_id");
    if(t.number("SELECT COUNT(*) FROM "+DbSplit.full(review,"order_review")+" WHERE room_type_id IS NULL")!=0)throw new IllegalStateException("Orphan reviews must be reconciled before cutover");
    t.execute("UPDATE "+DbSplit.full(o,"booking_order")+" SET marketing_operation_id=CONCAT('legacy:',order_id)");
    t.execute("INSERT INTO "+DbSplit.full(m,"marketing_reservation")+"(operation_id,order_id,customer_id,deal_id,coupon_id,room_count,state,released,request_json) SELECT marketing_operation_id,order_id,customer_id,daily_deal_id,coupon_id,room_count,'COMMITTED',IF(order_status IN ('CANCELLED','CLOSED','REFUNDED'),1,0),'{}' FROM "+DbSplit.full(o,"booking_order"));
    t.execute("UPDATE "+DbSplit.full(m,"customer_coupon")+" c JOIN "+DbSplit.full(o,"booking_order")+" o ON c.order_id=o.order_id SET c.reservation_id=o.marketing_operation_id");
    for(var domain:PROJECTIONS.entrySet())for(var mapping:domain.getValue().entrySet()) {
      var key=mapping.getKey().split(":");
      projection(t,domains.get(key[0]),key[1],domains.get(domain.getKey()),mapping.getValue());
    }
    for(var entry:domains.entrySet()) {
      t.checkForeignKeys(entry.getValue());
      for(String table:OWNED.get(entry.getKey()))if(t.number("SELECT COUNT(*) FROM "+DbSplit.full(source,table))!=t.number("SELECT COUNT(*) FROM "+DbSplit.full(entry.getValue(),table)))throw new IllegalStateException("Owner table count changed: "+table);
    }
    return domains;
  }
  static void restoreProof(DbSplit t,String source,String suffix)throws Exception {
    Path backup=t.backup(source);
    String restored="sh_full_restore_"+suffix;
    t.createSchema(restored);
    t.restore(backup,restored);
    for(String table:t.tables(source))t.compare(source,restored,table,null);
    t.execute("DROP DATABASE "+DbSplit.ident(restored));
  }
  static void activate(DbSplit t,String source,Map<String,String> domains)throws Exception {
    Map<String,DbSplit.Credentials> principals=new LinkedHashMap<>();
    for(var entry:domains.entrySet()) {
      var account=t.createPrincipal("sh_"+entry.getKey()+"_app",entry.getValue(),new HashSet<>(t.tables(entry.getValue())));
      principals.put(entry.getKey(),account);
      try(var db=t.connect(entry.getValue(),account.user(),account.password())) {
        for(var other:domains.entrySet())if(!other.getKey().equals(entry.getKey()))t.denied(db,"SELECT * FROM "+DbSplit.full(other.getValue(),OWNED.get(other.getKey()).iterator().next())+" LIMIT 0");
        t.denied(db,"SELECT * FROM "+DbSplit.full(source,"booking_order")+" LIMIT 0");
        t.denied(db,"CREATE TABLE "+DbSplit.full(entry.getValue(),"_privilege_probe")+"(id INT)");
      }
    }
    var updates=new LinkedHashMap<String,String>();
    updates.put("FULL_DOMAIN_LAYOUT","split");
    updates.put("ORDER_LEGACY_ARCHIVE",source);
    for(var entry:domains.entrySet()) {
      String prefix=entry.getKey().toUpperCase(Locale.ROOT);
      var account=principals.get(entry.getKey());
      updates.put(prefix+"_MYSQL_DATABASE",entry.getValue());
      updates.put(prefix+"_MYSQL_USER",account.user());
      updates.put(prefix+"_MYSQL_PASSWORD",account.password());
    }
    var order=principals.get("order");
    updates.put("BUSINESS_MYSQL_DATABASE",domains.get("order"));
    updates.put("BUSINESS_MYSQL_USER",order.user());
    updates.put("BUSINESS_MYSQL_PASSWORD",order.password());
    // Freeze the old business application's table-level grants only after replacement accounts are verified.
    String old=t.env.get("BUSINESS_MYSQL_USER");
    for(String table:t.tables(source)) {
      long count=t.number("SELECT COUNT(*) FROM mysql.tables_priv WHERE User="+DbSplit.literal(old)+" AND Host='%' AND Db="+DbSplit.literal(source)+" AND Table_name="+DbSplit.literal(table));
      if(count>0) {
        t.execute("REVOKE ALL PRIVILEGES ON "+DbSplit.full(source,table)+" FROM "+DbSplit.literal(old)+"@'%'");
        t.execute("GRANT SELECT ON "+DbSplit.full(source,table)+" TO "+DbSplit.literal(old)+"@'%'");
      }
    }
    var lines=new ArrayList<String>();
    for(String line:Files.readAllLines(t.root.resolve(".env"),StandardCharsets.UTF_8)) {
      int at=line.indexOf('=');
      if(at<0||!updates.containsKey(line.substring(0,at)))lines.add(line);
    }
    updates.forEach((key,value)->lines.add(key+"="+value));
    Path next=t.root.resolve(".env.full-domains-tmp");
    Files.write(next,lines,StandardCharsets.UTF_8);
    Files.move(next,t.root.resolve(".env"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    t.report.setProperty("full.layout","nine independent domain schemas including existing user/hotel/inventory");
    t.report.setProperty("full.permissions","foreign domain and DDL denied; old business owner read only");
    t.saveReport("FULL_DOMAIN_CUTOVER_VERIFIED");
  }
  public static void main(String[] args)throws Exception {
    var t=new DbSplit(Path.of(args[0]),args[1],Integer.parseInt(args[2]));
    try {
      stopped(t);
      if(args.length>3&&args[3].equals("exportrollback")) {
        FullDomainRollback.export(t);
        return;
      }
      if(!"split".equals(t.env.get("INVENTORY_DATA_LAYOUT")))throw new IllegalStateException("Complete the four-domain baseline migration first");
      if(t.env.containsKey("FULL_DOMAIN_LAYOUT"))throw new IllegalStateException("Full domain layout already exists; do not repeat migration");
      String source=t.env.get("BUSINESS_MYSQL_DATABASE");
      if(t.number("SELECT COUNT(*) FROM "+DbSplit.full(source,"inventory_tcc_journal")+" WHERE state='PENDING'")!=0)throw new IllegalStateException("Resolve pending inventory decisions before migration");
      Files.copy(t.root.resolve(".env"),t.output.resolve("env-before-full-domain-split.private"));
      var grants=new ArrayList<String>();
      try(var st=t.admin.createStatement();var rs=st.executeQuery("SHOW GRANTS FOR "+DbSplit.literal(t.env.get("BUSINESS_MYSQL_USER"))+"@'%'")) {
        while(rs.next())grants.add(rs.getString(1));
      }
      Files.write(t.output.resolve("grants-before-full-domain-split.private.sql"),grants);
      String id=UUID.randomUUID().toString().substring(0,8);
      for(String db:List.of(source,t.env.get("USER_MYSQL_DATABASE"),t.env.get("HOTEL_MYSQL_DATABASE"),t.env.get("INVENTORY_MYSQL_DATABASE")))restoreProof(t,db,id+db.substring(db.lastIndexOf('_')));
      boolean rehearsal=args.length>3&&args[3].equals("rehearse");
      String suffix=rehearsal?"_proof_"+id:"";
      var domains=build(t,source,suffix);
      for(var db:domains.values())restoreProof(t,db,id+db.substring(db.lastIndexOf('_')));
      t.report.setProperty("full.restoreProof","all original and new owner schemas restored and checksums compared");
      if(rehearsal) {
        for(String db:domains.values())t.execute("DROP DATABASE "+DbSplit.ident(db));
        t.saveReport("FULL_DOMAIN_REHEARSAL_PASSED");
        System.out.println("Full-domain rehearsal passed. Private proof: "+t.output);
      }
      else {
        activate(t,source,domains);
        System.out.println("Full-domain migration verified; private backup and manifest: "+t.output);
      }
    }
    catch(Exception failure) {
      t.saveReport("FULL_DOMAIN_FAILED");
      throw failure;
    }
    finally {
      t.admin.close();
    }
  }
}
