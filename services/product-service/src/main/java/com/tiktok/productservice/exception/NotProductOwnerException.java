package com.tiktok.productservice.exception;

import com.tiktok.common.exception.ForbiddenException;

public class NotProductOwnerException extends ForbiddenException {

    public NotProductOwnerException(Long productId) {
        super("NOT_PRODUCT_OWNER", "You do not own product: " + productId);
    }
}
