package com.vetclinic.clinical.dto.response;

public record SpeciesResponse(
        Short id,
        String code,
        String name,
        String description,
        boolean active
) {
}
