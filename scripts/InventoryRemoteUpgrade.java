import java.nio.file.*;
import java.sql.*;
import java.util.*;
/** Stop-the-world cutover. Keeps the original backup and rehearses restore before changing permissions. */
class InventoryRemoteUpgrade {
 static final Set<String> OWNED=Set.of("room_inventory","inventory_operation","inventory_cache_sync","inventory_reservation","inventory_tcc_attempt");
 static String state="CASE WHEN order_status='WAIT_PAY' AND payment_status='UNPAID' THEN 'RESERVED' WHEN payment_status IN ('PAID','REFUNDING') AND order_status NOT IN ('REFUNDED','CANCELLED','CLOSED') THEN 'CONFIRMED' ELSE 'RELEASED' END";
 static void install(DbSplit t,String schema)throws Exception {
  t.execute("USE "+DbSplit.ident(schema));String ddl=Files.readString(t.root.resolve("database/18_inventory_tcc.sql")).replace("USE smart_hotel_cloud;","");
  for(String sql:ddl.split(";"))if(!sql.isBlank())t.execute(sql);
  if(t.number("SELECT COUNT(*) FROM "+DbSplit.full(schema,"inventory_tcc_attempt"))!=0||t.number("SELECT COUNT(*) FROM "+DbSplit.full(schema,"inventory_tcc_journal"))!=0)throw new IllegalStateException("Already used TCC tables; do not reseed active reservations");
  t.execute("INSERT INTO "+DbSplit.full(schema,"inventory_reservation")+" (order_id,room_type_id,check_in_date,check_out_date,room_count,state) SELECT order_id,room_type_id,check_in_date,check_out_date,room_count,"+state+" FROM "+DbSplit.full(schema,"booking_order")+" ON DUPLICATE KEY UPDATE order_id=inventory_reservation.order_id");
  String inv=DbSplit.full(schema,"room_inventory"),r=DbSplit.full(schema,"inventory_reservation");
  long mismatch=t.number("SELECT COUNT(*) FROM "+inv+" i LEFT JOIN (SELECT i.inventory_id,SUM(CASE WHEN r.state='RESERVED' THEN r.room_count ELSE 0 END) l,SUM(CASE WHEN r.state='CONFIRMED' THEN r.room_count ELSE 0 END) s FROM "+inv+" i LEFT JOIN "+r+" r ON r.room_type_id=i.room_type_id AND i.inventory_date>=r.check_in_date AND i.inventory_date<r.check_out_date GROUP BY i.inventory_id) x ON x.inventory_id=i.inventory_id WHERE i.locked_qty<>x.l OR i.sold_qty<>x.s OR i.locked_qty<0 OR i.sold_qty<0 OR i.total_qty<i.locked_qty+i.sold_qty");
  if(mismatch!=0)throw new IllegalStateException("Order/reservation/inventory occupancy mismatch: "+mismatch);
  var fks=new ArrayList<String>();try(var st=t.admin.createStatement();var rs=st.executeQuery("SELECT DISTINCT CONSTRAINT_NAME FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA="+DbSplit.literal(schema)+" AND TABLE_NAME='inventory_operation' AND REFERENCED_TABLE_NAME='booking_order'")){while(rs.next())fks.add(rs.getString(1));}
  for(String fk:fks)t.execute("ALTER TABLE "+DbSplit.full(schema,"inventory_operation")+" DROP FOREIGN KEY "+DbSplit.ident(fk));
 }
 static void grants(DbSplit t,String schema,String business,String owner)throws Exception {
  for(String table:OWNED){t.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON "+DbSplit.full(schema,table)+" TO "+DbSplit.literal(owner)+"@'%'");}
  t.execute("GRANT SELECT ON "+DbSplit.full(schema,"room_type")+" TO "+DbSplit.literal(owner)+"@'%'");
  for(String table:List.of("room_inventory","inventory_operation","inventory_cache_sync"))t.execute("REVOKE INSERT,UPDATE,DELETE ON "+DbSplit.full(schema,table)+" FROM "+DbSplit.literal(business)+"@'%'");
  t.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON "+DbSplit.full(schema,"inventory_tcc_journal")+" TO "+DbSplit.literal(business)+"@'%'");
 }
 public static void main(String[] args)throws Exception {
  var t=new DbSplit(Path.of(args[0]),args[1],Integer.parseInt(args[2]));
  try {
   t.stopRequired();if("remote".equals(t.env.get("INVENTORY_MODE")))throw new IllegalStateException("Remote inventory already active");
   String schema=t.env.get("BUSINESS_MYSQL_DATABASE"),business=t.env.get("BUSINESS_MYSQL_USER");DbSplit.ident(schema);DbSplit.ident(business);
   var original=t.tables(schema);var before=new HashMap<String,DbSplit.Fingerprint>();for(String table:original)before.put(table,t.fingerprint(schema,table,null));Path backup=t.backup(schema);Files.copy(t.root.resolve(".env"),t.output.resolve("env-before-inventory.private"));
   String rehearsal="sh_rehearsal_tcc_"+UUID.randomUUID().toString().substring(0,8);t.createSchema(rehearsal);t.restore(backup,rehearsal);
   for(String table:original)t.compare(schema,rehearsal,table,null);install(t,rehearsal);
   String suffix=UUID.randomUUID().toString().substring(0,8);var trialOwner=t.createPrincipal("sh_test_i_"+suffix,rehearsal,OWNED);var businessTables=t.businessOwned(rehearsal);businessTables.removeAll(Set.of("inventory_reservation","inventory_tcc_attempt"));var trialBusiness=t.createPrincipal("sh_test_ib_"+suffix,rehearsal,businessTables);grants(t,rehearsal,trialBusiness.user(),trialOwner.user());
   try(var db=t.connect(rehearsal,trialOwner.user(),trialOwner.password())){t.denied(db,"SELECT * FROM booking_order LIMIT 0");t.denied(db,"SELECT * FROM inventory_tcc_journal LIMIT 0");}
   try(var db=t.connect(rehearsal,trialBusiness.user(),trialBusiness.password())){t.denied(db,"UPDATE room_inventory SET total_qty=total_qty WHERE 1=0");}
   // Rehearse existing database-layout rollback with populated owner records and a pending decision.
   long id=t.number("SELECT COALESCE(MIN(order_id),0) FROM "+DbSplit.full(rehearsal,"booking_order"));
   t.execute("INSERT INTO "+DbSplit.full(rehearsal,"inventory_tcc_journal")+"(attempt_id,order_id,payload_json) VALUES('rollback-rehearsal',"+id+",'{}')");
   String merged="sh_rehearsal_tcc_merge_"+suffix;t.mergeCurrent(t.source,t.env.get("USER_MYSQL_DATABASE"),rehearsal,merged);
   for(String table:List.of("inventory_reservation","inventory_tcc_attempt","inventory_tcc_journal"))t.compare(rehearsal,merged,table,null);
   // The restored baseline was checked before install; rehearsal now contains intentional synthetic TCC rows.
   t.execute("DROP DATABASE "+DbSplit.ident(merged));t.execute("DROP DATABASE "+DbSplit.ident(rehearsal));
   t.execute("DROP USER "+DbSplit.literal(trialOwner.user())+"@'%'");t.execute("DROP USER "+DbSplit.literal(trialBusiness.user())+"@'%'");
   t.report.setProperty("inventory.restoreAndRollback","verified full restore, permissions and pending-decision preservation");t.saveReport("INVENTORY_REMOTE_REHEARSED");
   install(t,schema);for(String table:original)if(!before.get(table).equals(t.fingerprint(schema,table,null)))throw new IllegalStateException("Existing data changed during inventory migration: "+table);
   var owner=t.createPrincipal("sh_inventory_app",schema,OWNED);grants(t,schema,business,owner.user());
   try(var db=t.connect(schema,owner.user(),owner.password())){t.denied(db,"SELECT * FROM booking_order LIMIT 0");t.denied(db,"SELECT * FROM inventory_tcc_journal LIMIT 0");t.denied(db,"SELECT * FROM "+DbSplit.full(t.env.get("USER_MYSQL_DATABASE"),"sys_user")+" LIMIT 0");}
   try(var db=t.connect(schema,business,t.env.get("BUSINESS_MYSQL_PASSWORD"))){t.denied(db,"UPDATE room_inventory SET total_qty=total_qty WHERE 1=0");}
   var updates=Map.of("INVENTORY_MODE","remote","INVENTORY_MYSQL_DATABASE",schema,"INVENTORY_MYSQL_USER",owner.user(),"INVENTORY_MYSQL_PASSWORD",owner.password());
   var lines=new ArrayList<String>();for(String line:Files.readAllLines(t.root.resolve(".env"))){int at=line.indexOf('=');if(at<0||!updates.containsKey(line.substring(0,at)))lines.add(line);}updates.forEach((k,v)->lines.add(k+"="+v));
   Path tmp=t.root.resolve(".env.inventory-tmp");Files.write(tmp,lines);Files.move(tmp,t.root.resolve(".env"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
   t.report.setProperty("inventory.occupancy","all daily source quantities match preserved-order reservations");t.saveReport("INVENTORY_REMOTE_READY");System.out.println("Inventory owner cutover complete. Private backup and verified restore report: "+t.output);
  }catch(Exception e){t.saveReport("FAILED");throw e;}finally{t.admin.close();}
 }
}
