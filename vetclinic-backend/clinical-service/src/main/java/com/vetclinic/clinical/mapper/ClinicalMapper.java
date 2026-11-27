package com.vetclinic.clinical.mapper;

import com.vetclinic.clinical.dto.response.PetResponse;
import com.vetclinic.clinical.dto.response.SpeciesResponse;
import com.vetclinic.clinical.entity.Pet;
import com.vetclinic.clinical.entity.Species;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.WARN)
public interface ClinicalMapper {

    SpeciesResponse toResponse(Species species);

    @Mapping(target = "speciesId", source = "species.id")
    @Mapping(target = "speciesCode", source = "species.code")
    @Mapping(target = "speciesName", source = "species.name")
    @Mapping(target = "ownerName", source = "ownerNameSnapshot")
    @Mapping(target = "ownerPhone", source = "ownerPhoneSnapshot")
    // pets không còn cột weight (v3.0): cân nặng gần nhất lấy từ đợt khám gần nhất, PetServiceImpl gắn sau
    @Mapping(target = "weight", ignore = true)
    @Mapping(target = "withWeight", ignore = true)   // hàm gắn cân nặng của record, không phải thuộc tính
    PetResponse toResponse(Pet pet);
}
