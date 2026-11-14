package com.smarthotel.controller;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class HotelNoticeControllerTest {
    @Test void missingNoticeReturnsEmptyTextRatherThanBreakingTheHomePage() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new HotelNoticeController(new MockEnvironment())).build();
        mvc.perform(get("/api/v1/hotel/notice")).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data.message").value(""));
    }

    @Test void refreshedEnvironmentIsReadWithoutRecreatingControllerAndOnlyNoticeIsExposed() throws Exception {
        var environment = new MockEnvironment().withProperty("smarthotel.notice.message", "before")
                .withProperty("MYSQL_PASSWORD", "must-not-appear");
        var mvc = MockMvcBuilders.standaloneSetup(new HotelNoticeController(environment)).build();
        mvc.perform(get("/api/v1/hotel/notice")).andExpect(jsonPath("$.data.message").value("before"));
        environment.setProperty("smarthotel.notice.message", "after");
        mvc.perform(get("/api/v1/hotel/notice")).andExpect(jsonPath("$.data.message").value("after"))
                .andExpect(jsonPath("$.data.length()").value(1));
    }
}
