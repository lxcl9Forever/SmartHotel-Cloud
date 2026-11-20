package com.smarthotel.service;
import com.smarthotel.config.AlipayConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
@Service public class PaymentOptionsService {
 public record Options(String mode,boolean available,String message,boolean manualQuerySupported){}
 private final AlipayConfig config;private final boolean mock;
 public PaymentOptionsService(AlipayConfig config,@Value("${smarthotel.payment.mock-enabled:false}")boolean mock){this.config=config;this.mock=mock;}
 public Options options(){
  if(config.isEnabled()){
   boolean ready=false;
   try{var gateway=URI.create(config.getGateway());ready="https".equals(gateway.getScheme())&&gateway.getHost()!=null&&gateway.getHost().endsWith(".alipaydev.com")&&config.getAppId()!=null&&!config.getAppId().isBlank()&&Files.isReadable(Path.of(config.getPrivateKeyFile()))&&Files.isReadable(Path.of(config.getPublicKeyFile()));}catch(RuntimeException ignored){}
   return new Options("ALIPAY_SANDBOX",ready,ready?"使用支付宝沙箱买家账号付款；本机可主动查询支付结果，无需公网回调。":"支付宝沙箱配置不完整，请管理员检查本地应用及密钥配置。",true);
  }
  return mock?new Options("MOCK",true,"当前是显式启用的开发模拟支付，不会调用支付宝。",false):new Options("NONE",false,"支付渠道尚未启用，请管理员配置支付宝沙箱。",false);
 }
}
