package com.tiktok.paymentservice.mapper;

import com.tiktok.paymentservice.dto.response.PaymentResponse;
import com.tiktok.paymentservice.entity.PaymentTransaction;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface PaymentMapper {

    PaymentResponse toResponse(PaymentTransaction transaction);
}
