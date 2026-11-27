package com.vetclinic.billing.dto.response;

import java.math.BigDecimal;

public record IngredientResponse(String ingredientName, BigDecimal strengthValue, String strengthUnit) {
}
