package com.smarthotel.dto.ai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

@Data
public class RecommendRequest {
    @NotBlank
    @Size(max = 500)
    private String text;
    @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(10)
    private Integer roomCount = 1;
    private LocalDate checkInDate;
}
