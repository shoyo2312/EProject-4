package com.tiktok.authservice.service;

import com.tiktok.authservice.entity.AuthProvider;

/**
 * Turns a token the client got from a provider's SDK into a {@link SocialProfile}, or refuses it.
 *
 * <p>Refusing is the whole job. A token is only evidence of anything once it is proven to have been
 * issued <em>for this application</em>: both providers hand out tokens to every app, and one taken
 * from any other app would otherwise log its bearer in here as whoever it names.
 */
public interface SocialTokenVerifier {

    AuthProvider provider();

    /**
     * @throws com.tiktok.authservice.exception.InvalidSocialTokenException if the token is not
     *                                                                     valid, or was not issued
     *                                                                     for this application
     */
    SocialProfile verify(String token);
}
