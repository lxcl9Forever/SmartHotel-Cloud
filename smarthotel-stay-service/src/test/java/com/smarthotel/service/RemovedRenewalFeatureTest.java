package com.smarthotel.service;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RemovedRenewalFeatureTest {
 @Test void noRenewalControllersOrBusinessImplementationArePackaged(){for(String type:new String[]{"com.smarthotel.controller.CustomerStayController","com.smarthotel.controller.StaffStayController","com.smarthotel.service.impl.StayExtensionServiceImpl","com.smarthotel.dto.stay.ExtendStayRequest"})assertThrows(ClassNotFoundException.class,()->Class.forName(type));}
}
