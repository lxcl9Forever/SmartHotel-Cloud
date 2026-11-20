package com.smarthotel.service;

import com.smarthotel.dto.ai.RecommendRequest;
import com.smarthotel.dto.ai.RoomPreference;
import com.smarthotel.model.inventory.RoomInventory;
import com.smarthotel.model.hotel.RoomPrice;
import com.smarthotel.model.hotel.RoomType;
import com.smarthotel.exception.BusinessException;
import com.smarthotel.service.InventoryReadGateway;
import com.smarthotel.mapper.RoomRatingMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AIRecommendServiceTest {
    private final LLMClient llm = mock(LLMClient.class);
    private final HotelCatalog catalog = mock(HotelCatalog.class);
    private final InventoryReadGateway inventory = mock(InventoryReadGateway.class);
    private final RoomRatingMapper ratings = mock(RoomRatingMapper.class);
    private final PromotionService promotions = mock(PromotionService.class);
    private final AIRecommendService service = new AIRecommendService(
            llm, catalog, inventory, ratings, promotions);

    @Test
    void doesNotFallBackWhenModelIsUnavailable() {
        when(llm.parse(any())).thenThrow(new BusinessException(502, "智能选房模型调用失败"));
        RecommendRequest request = new RecommendRequest();
        request.setText("一家三口入住三晚，预算1000，希望安静");
        assertThrows(BusinessException.class, () -> service.recommend(request));
        verifyNoInteractions(catalog, inventory);
    }

    @Test
    void recommendsOnlyWhenEachNightHasInventoryAndPriceWithinBudget() {
        LocalDate firstDay = LocalDate.now().plusDays(1);
        when(llm.parse(any())).thenReturn(new LLMClient.Interpretation(
                new RoomPreference(3, 2, new BigDecimal("700"), "安静", "LLM"),
                "您好，感谢您告诉我入住需求。"));
        RoomType room = new RoomType();
        room.setRoomTypeId(1L);
        room.setTypeName("安静家庭房");
        room.setMaxGuests(3);
        when(catalog.activeTypes()).thenReturn(List.of(room));
        RoomInventory first = new RoomInventory();
        first.setTotalQty(2); first.setLockedQty(1); first.setSoldQty(0);
        RoomInventory second = new RoomInventory();
        second.setTotalQty(2); second.setLockedQty(0); second.setSoldQty(1);
        when(inventory.query(any(),any(),any())).thenReturn(List.of(first, second));
        RoomPrice firstPrice = new RoomPrice();
        firstPrice.setSalePrice(new BigDecimal("300"));
        RoomPrice secondPrice = new RoomPrice();
        secondPrice.setSalePrice(new BigDecimal("350"));
        when(catalog.priceIndex(List.of(1L), firstDay, firstDay.plusDays(2)))
                .thenReturn(Map.of(1L, List.of(firstPrice, secondPrice)));
        when(promotions.calculate(new BigDecimal("650")))
                .thenReturn(new PromotionService.Price(null, new BigDecimal("650"),
                        BigDecimal.ZERO, new BigDecimal("650")));
        RecommendRequest request = new RecommendRequest();
        request.setText("3人2晚，预算700，希望安静");
        request.setCheckInDate(firstDay);

        var result = service.recommend(request);
        assertEquals(1, result.recommendations().size());
        assertEquals(new BigDecimal("650"), result.recommendations().get(0).totalAmount());
        assertEquals(1, result.recommendations().get(0).availableQty());
        assertEquals(firstDay.plusDays(2), result.checkOutDate());
        assertTrue(result.reply().startsWith("您好，感谢您告诉我入住需求。"));
        verify(catalog).priceIndex(List.of(1L), firstDay, firstDay.plusDays(2));

        second.setSoldQty(2);
        assertTrue(service.recommend(request).recommendations().isEmpty());
        verify(catalog, times(2)).priceIndex(List.of(1L), firstDay, firstDay.plusDays(2));
    }

    @Test
    void suggestsClosestAvailableRoomAndOneNightShorterPriceWhenBudgetIsTooLow() {
        LocalDate checkIn = LocalDate.now().plusDays(1);
        when(llm.parse(any())).thenReturn(new LLMClient.Interpretation(
                new RoomPreference(3, 2, new BigDecimal("600"), "", "LLM"),
                "您好，感谢您的耐心等待。"));
        RoomType room = new RoomType();
        room.setRoomTypeId(7L);
        room.setTypeName("家庭房");
        room.setMaxGuests(3);
        RoomType expensiveRoom = new RoomType();
        expensiveRoom.setRoomTypeId(8L);
        expensiveRoom.setTypeName("豪华家庭房");
        expensiveRoom.setMaxGuests(3);
        when(catalog.activeTypes()).thenReturn(List.of(expensiveRoom, room));
        RoomInventory first = new RoomInventory();
        first.setTotalQty(1); first.setLockedQty(0); first.setSoldQty(0);
        RoomInventory second = new RoomInventory();
        second.setTotalQty(1); second.setLockedQty(0); second.setSoldQty(0);
        when(inventory.query(any(),any(),any())).thenReturn(List.of(first, second));
        RoomPrice firstPrice = new RoomPrice();
        firstPrice.setPriceDate(checkIn);
        firstPrice.setSalePrice(new BigDecimal("450"));
        RoomPrice secondPrice = new RoomPrice();
        secondPrice.setPriceDate(checkIn.plusDays(1));
        secondPrice.setSalePrice(new BigDecimal("500"));
        RoomPrice expensivePrice = new RoomPrice();
        expensivePrice.setSalePrice(new BigDecimal("600"));
        when(catalog.priceIndex(List.of(8L, 7L), checkIn, checkIn.plusDays(2)))
                .thenReturn(Map.of(8L, List.of(expensivePrice, expensivePrice),
                        7L, List.of(firstPrice, secondPrice)));
        when(promotions.calculate(any())).thenAnswer(call -> {
            BigDecimal amount = call.getArgument(0);
            return new PromotionService.Price(null, amount, BigDecimal.ZERO, amount);
        });
        RecommendRequest request = new RecommendRequest();
        request.setText("3人住2晚，预算600");
        request.setCheckInDate(checkIn);

        var result = service.recommend(request);
        assertTrue(result.recommendations().isEmpty());
        assertNotNull(result.budgetAlternative());
        assertEquals(7L, result.budgetAlternative().room().roomTypeId());
        assertEquals(new BigDecimal("350"), result.budgetAlternative().additionalBudget());
        assertEquals(new BigDecimal("450"), result.budgetAlternative().shorterTotalAmount());
        assertEquals(checkIn.plusDays(1), result.budgetAlternative().shorterCheckOutDate());
        assertTrue(result.reply().contains("增加¥350"));
        assertTrue(result.reply().contains("可以控制在您的预算内"));
        verify(catalog).priceIndex(List.of(8L, 7L), checkIn, checkIn.plusDays(2));

        when(llm.parse(any())).thenReturn(new LLMClient.Interpretation(
                new RoomPreference(3, 2, new BigDecimal("300"), "", "LLM"),
                "您好，感谢您的耐心等待。"));
        when(catalog.priceIndex(List.of(8L, 7L), checkIn, checkIn.plusDays(2)))
                .thenReturn(Map.of(8L, List.of(expensivePrice, expensivePrice),
                        7L, List.of(firstPrice, secondPrice)));
        var lowerBudget = service.recommend(request);
        assertTrue(lowerBudget.reply().contains("仍比预算多¥150"));
        verify(catalog, times(2)).priceIndex(List.of(8L, 7L), checkIn, checkIn.plusDays(2));
    }
    @Test void multiRoomQuoteUsesCapacityStockAndTotalBudget(){
        var day=LocalDate.now().plusDays(1);when(llm.parse(any())).thenReturn(new LLMClient.Interpretation(new RoomPreference(4,1,new BigDecimal("700"),"","LLM"),"您好，很高兴为您挑选。"));
        var room=new RoomType();room.setRoomTypeId(1L);room.setTypeName("双人房");room.setMaxGuests(2);when(catalog.activeTypes()).thenReturn(List.of(room));
        var stock=new RoomInventory();stock.setTotalQty(3);stock.setLockedQty(0);stock.setSoldQty(0);when(inventory.query(any(),any(),any())).thenReturn(List.of(stock));
        var price=new RoomPrice();price.setSalePrice(new BigDecimal("300"));when(catalog.priceIndex(any(),any(),any())).thenReturn(Map.of(1L,List.of(price)));
        when(promotions.calculate(any())).thenAnswer(call->{BigDecimal amount=call.getArgument(0);return new PromotionService.Price(null,amount,BigDecimal.ZERO,amount);});
        var request=new RecommendRequest();request.setText("四人住一晚");request.setRoomCount(2);request.setCheckInDate(day);
        var result=service.recommend(request);assertEquals(2,result.roomCount());assertEquals(new BigDecimal("600"),result.recommendations().get(0).totalAmount());
        stock.setTotalQty(1);assertTrue(service.recommend(request).recommendations().isEmpty());
    }
}
