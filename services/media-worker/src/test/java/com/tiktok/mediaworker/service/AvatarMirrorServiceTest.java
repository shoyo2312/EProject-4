package com.tiktok.mediaworker.service;

import com.tiktok.mediaworker.config.MinioProperties;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.http.HttpClient;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AvatarMirrorServiceTest {

    private static final MinioProperties PROPERTIES =
            new MinioProperties("http://localhost:9000", "key", "secret", "video-media");

    private static final List<String> ALLOWED = List.of("lh3.googleusercontent.com");

    @Mock
    private MinioClient minioClient;

    private AvatarMirrorService service() {
        return new AvatarMirrorService(minioClient, PROPERTIES, ALLOWED, 5_242_880L);
    }

    /**
     * The event names the URL, so one that reached the topic from anywhere else could aim this
     * worker at the cluster's own network. Refusing before the request is what stops that from
     * being a fetch we perform on an attacker's behalf.
     */
    @Test
    void mirror_refusesAHostThatIsNotAllowed() {
        assertThatThrownBy(() -> service().mirror(1L, "https://evil.example.com/avatar.jpg"))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(minioClient);
    }

    /** http would let anyone on the path swap the picture, and every provider serves https. */
    @Test
    void mirror_refusesPlainHttp() {
        assertThatThrownBy(() -> service().mirror(1L, "http://lh3.googleusercontent.com/a/x"))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(minioClient);
    }

    /**
     * The client must not follow redirects on its own. If it does, the request to the next host
     * has already gone out by the time the allow-list sees it, and an open redirect on a trusted
     * provider becomes a fetch this worker performs against the cluster's own network — checking
     * the final URI afterwards only refuses to read a page it has already loaded. download()
     * walks the chain by hand so every hop is checked before it is requested.
     */
    @Test
    void httpClient_doesNotFollowRedirectsItself() {
        assertThat(service().httpClient.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER);
    }

    /**
     * Every sign-in announces the same picture. Without this the provider would be fetched on each
     * one, and repeating the announcement is deliberate rather than accidental.
     */
    @Test
    void mirror_alreadyCopied_skipsTheDownload() throws Exception {
        when(minioClient.statObject(any(StatObjectArgs.class)))
                .thenReturn(mock(StatObjectResponse.class));

        String url = service().mirror(42L, "https://lh3.googleusercontent.com/a/x");

        assertThat(url).isEqualTo("http://localhost:9000/video-media/avatars/42.jpg");
        verify(minioClient, never()).putObject(any(PutObjectArgs.class));
    }
}
