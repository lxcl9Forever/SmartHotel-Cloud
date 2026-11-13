package com.smarthotel.contract;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.List;
public final class HotelCatalogContract {
 private HotelCatalogContract(){}
 public record TypeQuery(List<Long> ids){}
 public record PriceQuery(List<Long> ids,LocalDate from,LocalDate to){}
 public record HotelReply(HotelSnapshot hotel){}

 @Data public static class HotelSnapshot {
  private Long hotelId;
  private String hotelName;
  private String address;
  private String phone;
  private String description;
  private LocalTime checkInTime;
  private LocalTime checkOutTime;
  private Integer status;
  private LocalDateTime createTime;
  private LocalDateTime updateTime;
 }

 @Data public static class TypeSnapshot {
  private Long roomTypeId;
  private String typeCode;
  private String typeName;
  private BigDecimal area;
  private String bedType;
  private Integer bedCount;
  private Integer maxGuests;
  private Integer totalRooms;
  private BigDecimal basePrice;
  private String description;
  private Integer status;
  private Integer sortNo;
  private LocalDateTime createTime;
  private LocalDateTime updateTime;
 }

 @Data public static class ImageSnapshot {
  private Long imageId;
  private Long roomTypeId;
  private String imageUrl;
  private Integer isCover;
  private Integer sortNo;
  private LocalDateTime createTime;
 }

 @Data public static class PriceSnapshot {
  private Long priceId;
  private Long roomTypeId;
  private LocalDate priceDate;
  private BigDecimal salePrice;
  private LocalDateTime createTime;
  private LocalDateTime updateTime;
 }
}
