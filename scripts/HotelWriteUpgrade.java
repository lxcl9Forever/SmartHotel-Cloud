import java.nio.file.*;import java.util.*;
class HotelWriteUpgrade {
 public static void main(String[] args)throws Exception {
  var t=new DbSplit(Path.of(args[0]),args[1],Integer.parseInt(args[2]));
  try {
   t.stopRequired();if("enabled".equals(t.env.get("HOTEL_WRITE_OWNER")))throw new IllegalStateException("Hotel writer already configured");
   String schema=t.env.get("BUSINESS_MYSQL_DATABASE"),business=t.env.get("BUSINESS_MYSQL_USER");DbSplit.ident(schema);DbSplit.ident(business);
   Path backup=t.backup(schema);String restored="sh_hotel_restore_"+UUID.randomUUID().toString().substring(0,8);t.createSchema(restored);t.restore(backup,restored);
   for(String table:t.tables(schema))t.compare(schema,restored,table,null);t.checkForeignKeys(restored);
   t.execute("DROP DATABASE "+DbSplit.ident(restored));t.saveReport("HOTEL_WRITE_BACKUP_RESTORED");
   var owner=t.createPrincipal("sh_hotel_app",schema,new HashSet<>());
   for(String table:List.of("hotel_info","room_type","room_type_image","room_price","room_inventory","room"))t.execute("GRANT SELECT ON "+DbSplit.full(schema,table)+" TO 'sh_hotel_app'@'%'");
   t.execute("GRANT UPDATE ON "+DbSplit.full(schema,"room_price")+" TO 'sh_hotel_app'@'%'");
   try(var db=t.connect(schema,owner.user(),owner.password())) {
    t.denied(db,"SELECT * FROM booking_order LIMIT 0");t.denied(db,"UPDATE room_inventory SET total_qty=total_qty WHERE 1=0");t.denied(db,"UPDATE room SET status=status WHERE 1=0");t.denied(db,"SELECT * FROM "+DbSplit.full(t.env.get("USER_MYSQL_DATABASE"),"sys_user")+" LIMIT 0");
   }
   t.execute("REVOKE INSERT,UPDATE,DELETE ON "+DbSplit.full(schema,"room_price")+" FROM "+DbSplit.literal(business)+"@'%'");
   try(var db=t.connect(schema,business,t.env.get("BUSINESS_MYSQL_PASSWORD"))){t.denied(db,"UPDATE room_price SET sale_price=sale_price WHERE 1=0");}
   Files.copy(t.root.resolve(".env"),t.output.resolve("env-before-hotel-write.private"));
   var updates=Map.of("HOTEL_MYSQL_USER",owner.user(),"HOTEL_MYSQL_PASSWORD",owner.password(),"HOTEL_WRITE_OWNER","enabled");var lines=new ArrayList<String>();
   for(String line:Files.readAllLines(t.root.resolve(".env"))){int at=line.indexOf('=');if(at<0||!updates.containsKey(line.substring(0,at)))lines.add(line);}updates.forEach((k,v)->lines.add(k+"="+v));
   Path tmp=t.root.resolve(".env.hotel-write-next");Files.write(tmp,lines);Files.move(tmp,t.root.resolve(".env"),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
   t.saveReport("HOTEL_WRITE_READY");System.out.println("Hotel price ownership and permissions verified. Private backup: "+t.output);
  }catch(Exception e){t.saveReport("FAILED");throw e;}finally{t.admin.close();}
 }
}
