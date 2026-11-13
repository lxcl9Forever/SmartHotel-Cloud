import java.nio.file.*;import java.sql.*;import java.util.*;import java.security.*;
class ProvisionHotel {
 public static void main(String[] args)throws Exception {
  Path root=Path.of(args[0]),file=root.resolve(".env");String original=Files.readString(file);var env=new HashMap<String,String>();for(String line:original.split("\\R")){int i=line.indexOf('=');if(i>0&&!line.startsWith("#"))env.put(line.substring(0,i),line.substring(i+1));}
  if(env.containsKey("HOTEL_MYSQL_PASSWORD"))throw new IllegalStateException("Hotel account already configured; refuse reprovisioning");
  byte[] bytes=new byte[36];new SecureRandom().nextBytes(bytes);String pass=Base64.getEncoder().encodeToString(bytes);
  try(var db=DriverManager.getConnection("jdbc:mysql://"+args[1]+":"+Integer.parseInt(args[2])+"/mysql?useSSL=false&allowPublicKeyRetrieval=true","root",env.get("MYSQL_ROOT_PASSWORD"));var st=db.createStatement()) {
   st.execute("CREATE USER 'sh_hotel_read'@'%' IDENTIFIED BY '"+pass+"'");
   for(String table:List.of("hotel_info","room_type","room_type_image","room_price","room_inventory"))st.execute("GRANT SELECT ON smart_hotel_business."+table+" TO 'sh_hotel_read'@'%'");
  }
  try(var db=DriverManager.getConnection("jdbc:mysql://"+args[1]+":"+Integer.parseInt(args[2])+"/smart_hotel_business?useSSL=false&allowPublicKeyRetrieval=true","sh_hotel_read",pass);var st=db.createStatement()){
   st.executeQuery("SELECT COUNT(*) FROM room_type").close();
   for(String q:List.of("UPDATE room_inventory SET total_qty=total_qty WHERE 1=0","SELECT * FROM booking_order LIMIT 0","SELECT * FROM smart_hotel_user.sys_user LIMIT 0")) {
    try{st.execute(q);throw new IllegalStateException("Unexpected hotel permission");}catch(SQLException e){if(!Set.of(1044,1142).contains(e.getErrorCode()))throw e;}
   }
  }
  Files.copy(file,root.resolve(".run/env-before-hotel-read.private"));
  Path next=root.resolve(".env.hotel-next");Files.writeString(next,original+"\nHOTEL_MYSQL_DATABASE=smart_hotel_business\nHOTEL_MYSQL_USER=sh_hotel_read\nHOTEL_MYSQL_PASSWORD="+pass+"\n");Files.move(next,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
  System.out.println("Hotel SELECT-only principal verified; private configuration saved.");
 }
}
