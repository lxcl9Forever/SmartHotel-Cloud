package com.smarthotel.service;

import com.smarthotel.client.HotelCatalogClient;
import com.smarthotel.contract.HotelCatalogContract.PriceQuery;
import com.smarthotel.contract.HotelCatalogContract.TypeQuery;
import com.smarthotel.model.hotel.RoomPrice;
import com.smarthotel.contract.HotelCatalogContract.TypeSnapshot;
import com.smarthotel.contract.HotelCatalogContract.PriceSnapshot;
import com.smarthotel.exception.BusinessException;
import feign.FeignException;
import feign.Request;
import feign.Response;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HotelCatalogTest {
    private static final String TOKEN = "synthetic-catalog-unit-test-token";
    private final HotelCatalogClient client = mock(HotelCatalogClient.class);
    private final HotelCatalog catalog = new HotelCatalog(client, TOKEN);

    @Test
    void typeLookupBatchesMoreThanOneHundredIdsAndDeduplicatesNullElements() {
        List<Long> unique = LongStream.rangeClosed(1, 205).boxed().toList();
        List<Long> input = new ArrayList<>(Arrays.asList(null, 1L, 1L));
        input.addAll(unique);
        input.addAll(Arrays.asList(205L, null));
        when(client.types(eq(TOKEN), any())).thenAnswer(call -> {
            TypeQuery query = call.getArgument(1);
            return query.ids().stream().map(id -> room(id, 1)).toList();
        });

        var result = catalog.types(input);

        assertEquals(unique, new ArrayList<>(result.keySet()));
        ArgumentCaptor<TypeQuery> queries = ArgumentCaptor.forClass(TypeQuery.class);
        verify(client, times(3)).types(eq(TOKEN), queries.capture());
        assertEquals(List.of(100, 100, 5), queries.getAllValues().stream()
                .map(query -> query.ids().size()).toList());
        assertEquals(unique, queries.getAllValues().stream()
                .flatMap(query -> query.ids().stream()).toList());
        verifyNoMoreInteractions(client);
    }

    @Test
    void findRetainsInactiveMetadataAndReturnsNullForAbsentType() {
        TypeSnapshot inactive = room(7L, 0);
        when(client.types(TOKEN, new TypeQuery(List.of(7L)))).thenReturn(List.of(inactive));
        when(client.types(TOKEN, new TypeQuery(List.of(404L)))).thenReturn(List.of());

        var found = catalog.find(7L);
        assertEquals(inactive.getRoomTypeId(), found.getRoomTypeId());
        assertEquals(inactive.getTypeName(), found.getTypeName());
        assertEquals(0, catalog.find(7L).getStatus());
        assertNull(catalog.find(404L));
        verify(client, times(2)).types(TOKEN, new TypeQuery(List.of(7L)));
        verify(client).types(TOKEN, new TypeQuery(List.of(404L)));
        verifyNoMoreInteractions(client);
    }

    @Test
    void priceIndexGroupsAndSortsNightlyRowsAcrossIdAndDateChunks() {
        LocalDate from = LocalDate.of(2030, 1, 1);
        LocalDate to = from.plusDays(370);
        List<Long> unique = LongStream.rangeClosed(1, 101).boxed().toList();
        List<Long> input = new ArrayList<>(unique);
        input.addAll(Arrays.asList(null, 1L, 101L));
        when(client.prices(eq(TOKEN), any())).thenAnswer(call -> {
            PriceQuery query = call.getArgument(1);
            List<PriceSnapshot> rows = new ArrayList<>();
            List<Long> reversedIds = new ArrayList<>(query.ids());
            Collections.reverse(reversedIds);
            for (Long id : reversedIds) {
                rows.add(price(id, query.to().minusDays(1)));
                rows.add(price(id, query.from()));
            }
            return rows;
        });

        var result = catalog.priceIndex(input, from, to);

        assertEquals(101, result.size());
        List<LocalDate> expectedDates = List.of(from, from.plusDays(365),
                from.plusDays(366), to.minusDays(1));
        for (Long id : unique) {
            List<RoomPrice> rows = result.get(id);
            assertEquals(expectedDates, rows.stream().map(RoomPrice::getPriceDate).toList());
            assertTrue(rows.stream().allMatch(row -> id.equals(row.getRoomTypeId())));
            assertTrue(rows.stream().allMatch(row -> BigDecimal.valueOf(id).equals(row.getSalePrice())));
        }
        ArgumentCaptor<PriceQuery> queries = ArgumentCaptor.forClass(PriceQuery.class);
        verify(client, times(4)).prices(eq(TOKEN), queries.capture());
        for (PriceQuery query : queries.getAllValues()) {
            assertTrue(query.ids().size() <= 100);
            assertEquals(query.ids().size(), query.ids().stream().distinct().count());
            assertTrue(query.ids().stream().allMatch(java.util.Objects::nonNull));
            assertTrue(query.from().isBefore(query.to()));
            assertTrue(ChronoUnit.DAYS.between(query.from(), query.to()) <= 366);
        }
        for (Long id : unique) {
            List<PriceQuery> spans = queries.getAllValues().stream()
                    .filter(query -> query.ids().contains(id))
                    .sorted(java.util.Comparator.comparing(PriceQuery::from)).toList();
            assertEquals(2, spans.size());
            assertEquals(from, spans.get(0).from());
            assertEquals(from.plusDays(366), spans.get(0).to());
            assertEquals(spans.get(0).to(), spans.get(1).from());
            assertEquals(to, spans.get(1).to());
        }
        verifyNoMoreInteractions(client);
    }

    @Test
    void emptyAndNullIdsAndInvalidDatesAvoidUpstreamCalls() {
        LocalDate day = LocalDate.of(2030, 1, 1);
        assertTrue(catalog.types(List.of()).isEmpty());
        assertTrue(catalog.types(Arrays.asList(null, null)).isEmpty());
        assertNull(catalog.find(null));
        assertTrue(catalog.priceIndex(List.of(), day, day.plusDays(1)).isEmpty());
        assertTrue(catalog.priceIndex(Arrays.asList(null, null), day, day.plusDays(1)).isEmpty());
        assertTrue(catalog.priceIndex(List.of(1L), null, day).isEmpty());
        assertTrue(catalog.priceIndex(List.of(1L), day, null).isEmpty());
        assertTrue(catalog.priceIndex(List.of(1L), day, day).isEmpty());
        assertTrue(catalog.priceIndex(List.of(1L), day.plusDays(1), day).isEmpty());
        verifyNoInteractions(client);
    }

    @Test
    void absentNightlyPricesRemainEmptyWithoutInventingPriceRows() {
        LocalDate day = LocalDate.of(2030, 1, 1);
        when(client.prices(TOKEN, new PriceQuery(List.of(3L), day, day.plusDays(1))))
                .thenReturn(List.of());

        assertTrue(catalog.prices(3L, day, day.plusDays(1)).isEmpty());
        verify(client).prices(TOKEN, new PriceQuery(List.of(3L), day, day.plusDays(1)));
        verifyNoMoreInteractions(client);
    }

    @Test
    void upstreamFeignFailuresBecome503InsteadOfEmptyResults() {
        LocalDate day = LocalDate.of(2030, 1, 1);
        when(client.types(eq(TOKEN), any())).thenThrow(failure(404));
        when(client.prices(eq(TOKEN), any())).thenThrow(failure(503));
        when(client.active(TOKEN)).thenThrow(failure(503));

        assertUnavailable(() -> catalog.find(1L));
        assertUnavailable(() -> catalog.prices(1L, day, day.plusDays(1)));
        assertUnavailable(catalog::activeTypes);
        verify(client).types(TOKEN, new TypeQuery(List.of(1L)));
        verify(client).prices(TOKEN, new PriceQuery(List.of(1L), day, day.plusDays(1)));
        verify(client).active(TOKEN);
        verifyNoMoreInteractions(client);
    }

    @Test
    void nullUpstreamResponsesBecome503InsteadOfMissingData() {
        LocalDate day = LocalDate.of(2030, 1, 1);
        when(client.types(eq(TOKEN), any())).thenReturn(null);
        when(client.prices(eq(TOKEN), any())).thenReturn(null);
        when(client.active(TOKEN)).thenReturn(null);
        when(client.hotel(TOKEN)).thenReturn(null);
        when(client.images(TOKEN, 1L)).thenReturn(null);

        assertUnavailable(() -> catalog.types(List.of(1L)));
        assertUnavailable(() -> catalog.priceIndex(List.of(1L), day, day.plusDays(1)));
        assertUnavailable(catalog::activeTypes);
        assertUnavailable(catalog::hotel);
        assertUnavailable(() -> catalog.images(1L));
    }

    @Test
    void laterTypeBatchFailureDoesNotReturnPartialMetadata() {
        List<Long> ids = LongStream.rangeClosed(1, 101).boxed().toList();
        when(client.types(eq(TOKEN), any()))
                .thenReturn(List.of(room(1L, 1)))
                .thenThrow(failure(503));

        assertUnavailable(() -> catalog.types(ids));
        verify(client, times(2)).types(eq(TOKEN), any());
    }

    @Test
    void laterDateChunkFailureDoesNotReturnPartialNightlyPrices() {
        LocalDate day = LocalDate.of(2030, 1, 1);
        when(client.prices(eq(TOKEN), any()))
                .thenReturn(List.of(price(1L, day)))
                .thenThrow(failure(503));

        assertUnavailable(() -> catalog.priceIndex(List.of(1L), day, day.plusDays(367)));
        verify(client, times(2)).prices(eq(TOKEN), any());
    }

    private void assertUnavailable(Runnable operation) {
        BusinessException error = assertThrows(BusinessException.class, operation::run);
        assertEquals(503, error.getCode());
    }

    private TypeSnapshot room(Long id, int status) {
        TypeSnapshot room = new TypeSnapshot();
        room.setRoomTypeId(id);
        room.setStatus(status);
        room.setTypeName("Room " + id);
        return room;
    }

    private PriceSnapshot price(Long id, LocalDate date) {
        PriceSnapshot price = new PriceSnapshot();
        price.setRoomTypeId(id);
        price.setPriceDate(date);
        price.setSalePrice(BigDecimal.valueOf(id));
        return price;
    }

    private FeignException failure(int status) {
        return FeignException.errorStatus("hotelCatalog", Response.builder()
                .status(status).reason("synthetic test failure").headers(Map.of())
                .request(Request.create(Request.HttpMethod.POST, "http://catalog.test/internal",
                        Map.of(), new byte[0], null, null)).build());
    }
    @Test
    void invalidNonPositiveIdsRetainMissingLookupSemantics() {
        assertNull(catalog.find(-1L));
        assertNull(catalog.find(0L));
        assertTrue(catalog.types(List.of(-1L,0L)).isEmpty());
        verifyNoInteractions(client);
    }}
