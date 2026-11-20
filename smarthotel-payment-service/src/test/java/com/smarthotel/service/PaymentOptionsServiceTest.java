package com.smarthotel.service;
import com.smarthotel.config.AlipayConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class PaymentOptionsServiceTest {
 @TempDir Path temp;
 @Test void disabledChannelNeverSilentlyEnablesMock(){var o=new PaymentOptionsService(new AlipayConfig(),false).options();assertEquals("NONE",o.mode());assertFalse(o.available());}
 @Test void mockRequiresExplicitConfiguration(){assertEquals("MOCK",new PaymentOptionsService(new AlipayConfig(),true).options().mode());}
 @Test void brokenSandboxDoesNotFallBackToMock(){var c=new AlipayConfig();c.setEnabled(true);var o=new PaymentOptionsService(c,true).options();assertEquals("ALIPAY_SANDBOX",o.mode());assertFalse(o.available());}
 @Test void onlySandboxWithReadableKeyFilesIsAvailable()throws Exception{var c=new AlipayConfig();c.setEnabled(true);c.setAppId("synthetic-sandbox");c.setGateway("https://openapi-sandbox.dl.alipaydev.com/gateway.do");var key=Files.writeString(temp.resolve("synthetic.txt"),"fixture");c.setPrivateKeyFile(key.toString());c.setPublicKeyFile(key.toString());assertTrue(new PaymentOptionsService(c,false).options().available());c.setGateway("https://openapi.alipay.com/gateway.do");assertFalse(new PaymentOptionsService(c,false).options().available());}
}
