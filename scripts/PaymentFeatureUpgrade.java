import java.nio.file.*;import java.nio.charset.StandardCharsets;import java.util.*;
class PaymentFeatureUpgrade {
 public static void main(String[] args)throws Exception{
  var t=new DbSplit(Path.of(args[0]),args[1],Integer.parseInt(args[2]));
  try{
   FullDomainSplit.stopped(t);if(!"split".equals(t.env.get("FULL_DOMAIN_LAYOUT")))throw new IllegalStateException("Nine-domain baseline required");
   String db=t.env.get("PAYMENT_MYSQL_DATABASE");
   FullDomainSplit.restoreProof(t,db,UUID.randomUUID().toString().substring(0,8)+"payment_feature");
   long before=t.number("SELECT COUNT(*) FROM "+DbSplit.full(db,"payment"));
   t.execute("USE "+DbSplit.ident(db));t.execute(Files.readString(t.root.resolve("scripts/payment-channel-schema.sql")));
   t.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON "+DbSplit.full(db,"payment_channel_audit")+" TO "+DbSplit.literal(t.env.get("PAYMENT_MYSQL_USER"))+"@'%'");
   if(t.number("SELECT COUNT(*) FROM "+DbSplit.full(db,"payment"))!=before)throw new IllegalStateException("Payment history changed");
   try(var c=t.connect(db,t.env.get("PAYMENT_MYSQL_USER"),t.env.get("PAYMENT_MYSQL_PASSWORD"));var s=c.createStatement()){s.executeQuery("SELECT COUNT(*) FROM payment_channel_audit").close();t.denied(c,"SELECT * FROM "+DbSplit.full(t.env.get("ORDER_MYSQL_DATABASE"),"booking_order")+" LIMIT 0");}
   var lines=new ArrayList<String>();for(String line:Files.readAllLines(t.root.resolve(".env")))if(!line.startsWith("PAYMENT_AI_LAYOUT="))lines.add(line);lines.add("PAYMENT_AI_LAYOUT=enabled");
   Files.copy(t.root.resolve(".env"),t.output.resolve("env-before-payment-upgrade.private"));var tmp=t.root.resolve(".env.payment-upgrade-tmp");Files.write(tmp,lines,StandardCharsets.UTF_8);Files.move(tmp,t.root.resolve(".env"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
   t.report.setProperty("payment.history.count",Long.toString(before));t.saveReport("PAYMENT_CHANNEL_AUDIT_READY");System.out.println("Payment channel audit initialized; history retained. Private backup: "+t.output);
  }catch(Exception failure){t.saveReport("PAYMENT_CHANNEL_AUDIT_FAILED");throw failure;}finally{t.admin.close();}
 }
}
