import java.nio.file.*;import java.util.*;
/** Remove metadata table access only after backup/restore verification. No business rows are rewritten. */
class HotelCatalogUpgrade {
 static final List<String> TABLES=List.of("hotel_info","room_type","room_type_image","room_price");
 public static void main(String[] args)throws Exception {
  var t=new DbSplit(Path.of(args[0]),args[1],Integer.parseInt(args[2]));
  try {
   t.stopRequired();if(!"enabled".equals(t.env.get("HOTEL_WRITE_OWNER")))throw new IllegalStateException("Complete hotel writer migration first");if("enabled".equals(t.env.get("HOTEL_CATALOG_REMOTE")))throw new IllegalStateException("Hotel catalog boundary already active");
   String schema=t.env.get("BUSINESS_MYSQL_DATABASE"),business=t.env.get("BUSINESS_MYSQL_USER");DbSplit.ident(schema);DbSplit.ident(business);
   Path backup=t.backup(schema);String restored="sh_catalog_restore_"+UUID.randomUUID().toString().substring(0,8);t.createSchema(restored);t.restore(backup,restored);for(String table:t.tables(schema))t.compare(schema,restored,table,null);t.checkForeignKeys(restored);
   t.execute("DROP DATABASE "+DbSplit.ident(restored));t.saveReport("HOTEL_CATALOG_BACKUP_RESTORED");Files.copy(t.root.resolve(".env"),t.output.resolve("env-before-catalog.private"));
   // Capture grants for manual recovery of a partially completed privilege cutover.
   var grants=new ArrayList<String>();try(var st=t.admin.createStatement();var rs=st.executeQuery("SHOW GRANTS FOR "+DbSplit.literal(business)+"@'%'")){while(rs.next())grants.add(rs.getString(1));}Files.write(t.output.resolve("business-grants-before.sql"),grants);
   for(String table:TABLES)t.execute("REVOKE ALL PRIVILEGES ON "+DbSplit.full(schema,table)+" FROM "+DbSplit.literal(business)+"@'%'");
   try(var db=t.connect(schema,business,t.env.get("BUSINESS_MYSQL_PASSWORD"));var st=db.createStatement()){
    for(String table:TABLES)t.denied(db,"SELECT * FROM "+DbSplit.full(schema,table)+" LIMIT 0");st.executeQuery("SELECT COUNT(*) FROM booking_order").close();st.executeQuery("SELECT COUNT(*) FROM room_inventory").close();
   }
   try(var db=t.connect(schema,t.env.get("HOTEL_MYSQL_USER"),t.env.get("HOTEL_MYSQL_PASSWORD"));var st=db.createStatement()){
    for(String table:TABLES)st.executeQuery("SELECT COUNT(*) FROM "+DbSplit.full(schema,table)).close();t.denied(db,"SELECT * FROM booking_order LIMIT 0");
   }
   Path temp=t.root.resolve(".env.catalog-next");Files.writeString(temp,Files.readString(t.root.resolve(".env"))+"\nHOTEL_CATALOG_REMOTE=enabled\n");Files.move(temp,t.root.resolve(".env"),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
   t.report.setProperty("catalog.permissions","business metadata SELECT and DML denied; hotel reads verified; order/inventory access preserved");t.saveReport("HOTEL_CATALOG_READY");System.out.println("Hotel catalog permissions verified. Private backup: "+t.output);
  }catch(Exception e){t.saveReport("FAILED");throw e;}finally{t.admin.close();}
 }
}
