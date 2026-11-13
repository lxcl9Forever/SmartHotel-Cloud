import java.nio.file.*;import java.sql.*;import java.util.*;
/** Independent schema for metadata, preserving original keys and verifying rollback against current hotel data. */
class HotelSplit {
 static void copyHotel(DbSplit t,String from,String to)throws Exception {
  t.createSchema(to);t.execute("SET FOREIGN_KEY_CHECKS=0");
  try{for(String table:DbSplit.HOTEL_TABLES){t.execute(t.qualify(t.ownedDdl(from,table,DbSplit.HOTEL_TABLES),to,table));t.copyRows(from,to,table,null,false);t.compare(from,to,table,null);}}finally{t.execute("SET FOREIGN_KEY_CHECKS=1");}t.checkForeignKeys(to);
 }
 static void checkRefs(DbSplit t,String business,String hotel)throws Exception {
  for(String table:List.of("booking_order","room","room_inventory","daily_deal","inventory_cache_sync","inventory_reservation")) {
   if(t.number("SELECT COUNT(*) FROM "+DbSplit.full(business,table)+" b LEFT JOIN "+DbSplit.full(hotel,"room_type")+" h ON b.room_type_id=h.room_type_id WHERE h.room_type_id IS NULL")!=0)throw new IllegalStateException("Room type reference mismatch in "+table);
  }
 }
 static void detach(DbSplit t,String business)throws Exception {
  var constraints=new ArrayList<String[]>();try(var st=t.admin.createStatement();var rs=st.executeQuery("SELECT DISTINCT TABLE_NAME,CONSTRAINT_NAME,REFERENCED_TABLE_NAME FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA="+DbSplit.literal(business)+" AND REFERENCED_TABLE_NAME IS NOT NULL")){while(rs.next())if(!DbSplit.HOTEL_TABLES.contains(rs.getString(1))&&DbSplit.HOTEL_TABLES.contains(rs.getString(3)))constraints.add(new String[]{rs.getString(1),rs.getString(2)});}
  for(var c:constraints)t.execute("ALTER TABLE "+DbSplit.full(business,c[0])+" DROP FOREIGN KEY "+DbSplit.ident(c[1]));
  t.report.setProperty("hotel.externalForeignKeysRemoved",constraints.stream().map(c->c[0]+"."+c[1]).sorted().toList().toString());t.checkForeignKeys(business);
 }
 static void revokeSnapshots(DbSplit t,String schema)throws Exception {
  var grants=new ArrayList<String[]>();try(var st=t.admin.createStatement();var rs=st.executeQuery("SELECT User,Host,Table_name FROM mysql.tables_priv WHERE Db="+DbSplit.literal(schema))){while(rs.next())if(DbSplit.HOTEL_TABLES.contains(rs.getString(3)))grants.add(new String[]{rs.getString(1),rs.getString(2),rs.getString(3)});}
  for(var g:grants)t.execute("REVOKE ALL PRIVILEGES ON "+DbSplit.full(schema,g[2])+" FROM "+DbSplit.literal(g[0])+"@"+DbSplit.literal(g[1]));
  String old=t.env.get("HOTEL_MYSQL_USER");for(String table:List.of("room","room_inventory"))t.execute("REVOKE ALL PRIVILEGES ON "+DbSplit.full(schema,table)+" FROM "+DbSplit.literal(old)+"@'%'");
 }
 public static void main(String[] args)throws Exception {
  var t=new DbSplit(Path.of(args[0]),args[1],Integer.parseInt(args[2]));
  try {
   t.stopRequired();if(!"enabled".equals(t.env.get("HOTEL_CATALOG_REMOTE")))throw new IllegalStateException("Complete catalog boundary first");if(t.env.containsKey("HOTEL_DATA_LAYOUT"))throw new IllegalStateException("Hotel data layout already configured");
   String business=t.env.get("BUSINESS_MYSQL_DATABASE"),user=t.env.get("USER_MYSQL_DATABASE"),target="smart_hotel_hotel";String id=UUID.randomUUID().toString().substring(0,8);checkRefs(t,business,business);
   Path backup=t.backup(business);t.backup(user);Files.copy(t.root.resolve(".env"),t.output.resolve("env-before-hotel-split.private"));
   var grants=new ArrayList<String>();for(String name:List.of(t.env.get("HOTEL_MYSQL_USER"),t.env.get("BUSINESS_MYSQL_USER"),t.env.get("INVENTORY_MYSQL_USER"))){try(var st=t.admin.createStatement();var rs=st.executeQuery("SHOW GRANTS FOR "+DbSplit.literal(name)+"@'%'")){while(rs.next())grants.add(rs.getString(1));}}Files.write(t.output.resolve("grants-before.sql"),grants);
   String restored="sh_hotel_b_"+id,rehearsal="sh_hotel_h_"+id,merged="sh_hotel_merge_"+id;t.createSchema(restored);t.restore(backup,restored);for(String table:t.tables(business))t.compare(business,restored,table,null);copyHotel(t,restored,rehearsal);detach(t,restored);checkRefs(t,restored,rehearsal);
   // Change only the isolated hotel copy: rollback must choose current hotel prices over stale business snapshots.
   t.execute("UPDATE "+DbSplit.full(rehearsal,"room_price")+" SET sale_price=sale_price+1 ORDER BY price_id LIMIT 1");
   t.execute("INSERT INTO "+DbSplit.full(restored,"inventory_tcc_journal")+"(attempt_id,order_id,payload_json) VALUES('hotel-split-rehearsal',1,'{}')");
   t.mergeWithHotel(t.source,user,restored,rehearsal,merged);
   for(String table:DbSplit.HOTEL_TABLES)t.compare(rehearsal,merged,table,null);
   for(String table:t.tables(restored))if(!DbSplit.HOTEL_TABLES.contains(table)&&!table.equals(DbSplit.AUDIT))t.compare(restored,merged,table,null);
   for(String db:List.of(merged,rehearsal,restored))t.execute("DROP DATABASE "+DbSplit.ident(db));
   t.report.setProperty("hotel.rollbackRehearsal","current hotel price overrides stale snapshot; current business data and pending inventory journal preserved");t.saveReport("HOTEL_SPLIT_REHEARSED");
   copyHotel(t,business,target);t.backup(target);checkRefs(t,business,target);detach(t,business);
   var owner=t.createPrincipal("sh_hotel_domain",target,Set.of());for(String table:DbSplit.HOTEL_TABLES)t.execute("GRANT SELECT ON "+DbSplit.full(target,table)+" TO 'sh_hotel_domain'@'%'");t.execute("GRANT UPDATE ON "+DbSplit.full(target,"room_price")+" TO 'sh_hotel_domain'@'%'");
   revokeSnapshots(t,business);
   try(var db=t.connect(target,owner.user(),owner.password());var st=db.createStatement()){
    for(String table:DbSplit.HOTEL_TABLES)st.executeQuery("SELECT COUNT(*) FROM "+DbSplit.full(target,table)).close();t.denied(db,"SELECT * FROM "+DbSplit.full(business,"room_inventory")+" LIMIT 0");t.denied(db,"SELECT * FROM "+DbSplit.full(business,"room")+" LIMIT 0");t.denied(db,"SELECT * FROM "+DbSplit.full(user,"sys_user")+" LIMIT 0");
   }
   for(String role:List.of("BUSINESS","INVENTORY"))try(var db=t.connect(business,t.env.get(role+"_MYSQL_USER"),t.env.get(role+"_MYSQL_PASSWORD"))){for(String table:DbSplit.HOTEL_TABLES){t.denied(db,"SELECT * FROM "+DbSplit.full(target,table)+" LIMIT 0");t.denied(db,"SELECT * FROM "+DbSplit.full(business,table)+" LIMIT 0");}}
   var updates=Map.of("HOTEL_DATA_LAYOUT","split","HOTEL_MYSQL_DATABASE",target,"HOTEL_MYSQL_USER",owner.user(),"HOTEL_MYSQL_PASSWORD",owner.password());var lines=new ArrayList<String>();for(String line:Files.readAllLines(t.root.resolve(".env"))){int at=line.indexOf('=');if(at<0||!updates.containsKey(line.substring(0,at)))lines.add(line);}updates.forEach((k,v)->lines.add(k+"="+v));Path tmp=t.root.resolve(".env.hotel-split-next");Files.write(tmp,lines);Files.move(tmp,t.root.resolve(".env"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
   t.saveReport("HOTEL_SPLIT_READY");System.out.println("Hotel schema split verified. Private backups and rehearsal: "+t.output);
  }catch(Exception e){t.saveReport("FAILED");throw e;}finally{t.admin.close();}
 }
}
