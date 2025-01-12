package io.kestra.plugin.fs.http;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.micronaut.http.*;
import io.micronaut.http.client.DefaultHttpClientConfiguration;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.HttpClientConfiguration;
import io.micronaut.http.client.multipart.MultipartBody;
import io.micronaut.http.client.netty.NettyHttpClientFactory;
import io.micronaut.http.ssl.ClientSslConfiguration;
import io.micronaut.logging.LogLevel;
import io.micronaut.rxjava2.http.client.RxStreamingHttpClient;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import javax.validation.constraints.NotNull;

import static io.kestra.core.utils.Rethrow.throwFunction;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
abstract public class AbstractHttp extends Task {
    @Schema(
        title = "The fully-qualified URIs that point to destination http server"
    )
    @PluginProperty(dynamic = true)
    @NotNull
    protected String uri;

    @Schema(
        title = "The http method to use"
    )
    @Builder.Default
    @PluginProperty
    protected HttpMethod method = HttpMethod.GET;

    @Schema(
        title = "The full body as string"
    )
    @PluginProperty(dynamic = true)
    protected String body;

    @Schema(
        title = "The form data to be send"
    )
    @PluginProperty(dynamic = true)
    protected Map<String, Object> formData;

    @Schema(
        title = "The request content type"
    )
    @PluginProperty(dynamic = true)
    protected String contentType;

    @Schema(
        title = "The header to pass to current request"
    )
    @PluginProperty(dynamic = true)
    protected Map<CharSequence, CharSequence> headers;

    @Schema(
        title = "The http request options"
    )
    protected RequestOptions options;

    @Schema(
        title = "The ssl request options"
    )
    protected SslOptions sslOptions;

    private static final NettyHttpClientFactory FACTORY = new NettyHttpClientFactory();

    protected DefaultHttpClientConfiguration configuration(RunContext runContext, HttpMethod httpMethod) throws IllegalVariableEvaluationException {
        DefaultHttpClientConfiguration configuration = new DefaultHttpClientConfiguration();

        if (this.options != null) {
            if (this.options.getConnectTimeout() != null) {
                configuration.setConnectTimeout(this.options.getConnectTimeout());
            }

            if (this.options.getReadTimeout() != null) {
                configuration.setReadTimeout(this.options.getReadTimeout());
            }

            if (this.options.getReadIdleTimeout() != null) {
                configuration.setReadIdleTimeout(this.options.getReadIdleTimeout());
            }

            if (this.options.getConnectionPoolIdleTimeout() != null) {
                configuration.setConnectionPoolIdleTimeout(this.options.getConnectionPoolIdleTimeout());
            }

            if (this.options.getMaxContentLength() != null) {
                configuration.setMaxContentLength(this.options.getMaxContentLength());
            }

            if (this.options.getProxyType() != null) {
                configuration.setProxyType(this.options.getProxyType());
            }

            if (this.options.getProxyAddress() != null && this.options.getProxyPort() != null) {
                configuration.setProxyAddress(new InetSocketAddress(
                    runContext.render(this.options.getProxyAddress()),
                    this.options.getProxyPort()
                ));
            }

            if (this.options.getProxyUsername() != null) {
                configuration.setProxyUsername(runContext.render(this.options.getProxyUsername()));
            }

            if (this.options.getProxyPassword() != null) {
                configuration.setProxyPassword(runContext.render(this.options.getProxyPassword()));
            }

            if (this.options.getDefaultCharset() != null) {
                configuration.setDefaultCharset(this.options.getDefaultCharset());
            }

            if (this.options.getFollowRedirects() != null) {
                configuration.setFollowRedirects(this.options.getFollowRedirects());
            }

            if (this.options.getLogLevel() != null) {
                configuration.setLogLevel(this.options.getLogLevel());
            }
        }

        if (httpMethod == HttpMethod.HEAD) {
            configuration.setMaxContentLength(Integer.MAX_VALUE);
        }

        ClientSslConfiguration clientSslConfiguration = new ClientSslConfiguration();

        if (this.sslOptions != null) {
            if (this.sslOptions.getInsecureTrustAllCertificates() != null) {
                clientSslConfiguration.setInsecureTrustAllCertificates(this.sslOptions.getInsecureTrustAllCertificates());
            }
        }

        configuration.setSslConfiguration(clientSslConfiguration);

        return configuration;
    }

    protected HttpClient client(RunContext runContext, HttpMethod httpMethod) throws IllegalVariableEvaluationException, MalformedURLException, URISyntaxException {
        URI from = new URI(runContext.render(this.uri));

        return FACTORY.createClient(from.toURL(), this.configuration(runContext, httpMethod));
    }

    protected RxStreamingHttpClient streamingClient(RunContext runContext, HttpMethod httpMethod) throws IllegalVariableEvaluationException, MalformedURLException, URISyntaxException {
        URI from = new URI(runContext.render(this.uri));

        return new ExtendedBridgedRxHttpClient(FACTORY.createStreamingClient(from.toURL(), this.configuration(runContext, httpMethod)));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    protected HttpRequest request(RunContext runContext) throws IllegalVariableEvaluationException, URISyntaxException, IOException {
        URI from = new URI(runContext.render(this.uri));

        MutableHttpRequest request = HttpRequest
            .create(method, from.toString());


        if (this.options != null && this.options.basicAuthUser != null && this.options.basicAuthPassword != null) {
            request.basicAuth(
                runContext.render(this.options.basicAuthUser),
                runContext.render(this.options.basicAuthPassword)
            );
        }

        if (this.formData != null) {
            if (MediaType.MULTIPART_FORM_DATA.equals(this.contentType)) {
                request.contentType(MediaType.MULTIPART_FORM_DATA);

                MultipartBody.Builder builder = MultipartBody.builder();
                for (Map.Entry<String, Object> e : this.formData.entrySet()) {
                    String key = runContext.render(e.getKey());

                    if (e.getValue() instanceof String) {
                        String render = runContext.render((String) e.getValue());

                        if (render.startsWith("kestra://")) {
                            File tempFile = runContext.tempFile().toFile();

                            try (OutputStream outputStream = new FileOutputStream(tempFile)) {
                                IOUtils.copyLarge(runContext.uriToInputStream(new URI(render)), outputStream);
                            }

                            builder.addPart(key, tempFile);
                        } else {
                            builder.addPart(key, render);
                        }
                    } else if (e.getValue() instanceof Map && ((Map<String, String>) e.getValue()).containsKey("name") && ((Map<String, String>) e.getValue()).containsKey("content")) {
                        String name = runContext.render(((Map<String, String>) e.getValue()).get("name"));
                        String content = runContext.render(((Map<String, String>) e.getValue()).get("content"));

                        File tempFile = runContext.tempFile().toFile();
                        File renamedFile = new File(Files.move(tempFile.toPath(), tempFile.toPath().resolveSibling(name)).toUri());

                        try (OutputStream outputStream = new FileOutputStream(renamedFile)) {
                            IOUtils.copyLarge(runContext.uriToInputStream(new URI(content)), outputStream);
                        }
                        
                        builder.addPart(key, renamedFile);
                    } else {
                        builder.addPart(key, JacksonMapper.ofJson().writeValueAsString(e.getValue()));
                    }
                }

                request.body(builder.build());
            } else {
                request.contentType(MediaType.APPLICATION_FORM_URLENCODED);
                request.body(runContext.render(this.formData));
            }
        } else if (this.body != null) {
            request.body(runContext.render(body));
        }

        if (this.contentType != null) {
            request.contentType(runContext.render(this.contentType));
        }

        if (this.headers != null) {
            request.headers(this.headers
                .entrySet()
                .stream()
                .map(throwFunction(e -> new AbstractMap.SimpleEntry<>(
                    e.getKey(),
                    runContext.render(e.getValue().toString())
                )))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))
            );
        }

        return request;
    }

    protected String[] tags(HttpRequest<String> request, HttpResponse<String> response) {
        ArrayList<String> tags = new ArrayList<>(
            Arrays.asList("request.method", request.getMethodName())
        );

        if (response != null) {
            tags.addAll(
                Arrays.asList("response.code", String.valueOf(response.getStatus().getCode()))
            );
        }

        return tags.toArray(String[]::new);
    }

    @Getter
    @Builder
    public static class RequestOptions {
        @Schema(title = "The connect timeout.")
        @PluginProperty(dynamic = false)
        private final Duration connectTimeout;

        @Schema(title = "The default read timeout.")
        @Builder.Default
        @PluginProperty(dynamic = false)
        private final Duration readTimeout = Duration.ofSeconds(HttpClientConfiguration.DEFAULT_READ_TIMEOUT_SECONDS);

        @Schema(title = "The default amount of time to allow read operation connections to remain idle.")
        @Builder.Default
        @PluginProperty(dynamic = false)
        private final Duration readIdleTimeout = Duration.of(HttpClientConfiguration.DEFAULT_READ_IDLE_TIMEOUT_MINUTES, ChronoUnit.MINUTES);

        @Schema(title = "The idle timeout for connection in the client connection pool. ")
        @Builder.Default
        @PluginProperty(dynamic = false)
        private final Duration connectionPoolIdleTimeout = Duration.ofSeconds(HttpClientConfiguration.DEFAULT_CONNECTION_POOL_IDLE_TIMEOUT_SECONDS);

        @Schema(title = "Sets the maximum content length the client can consume.")
        @Builder.Default
        @PluginProperty(dynamic = false)
        private final Integer maxContentLength = HttpClientConfiguration.DEFAULT_MAX_CONTENT_LENGTH;

        @Schema(title = "The proxy type to use.")
        @Builder.Default
        @PluginProperty(dynamic = false)
        private final Proxy.Type proxyType = Proxy.Type.DIRECT;

        @Schema(title = "The proxy to use.")
        @PluginProperty(dynamic = true)
        private final String proxyAddress;

        @Schema(title = "The proxy port to use.")
        @PluginProperty(dynamic = false)
        private final Integer proxyPort;

        @Schema(title = "The proxy user to use.")
        @PluginProperty(dynamic = true)
        private final String proxyUsername;

        @Schema(title = "The proxy password to use.")
        @PluginProperty(dynamic = true)
        private final String proxyPassword;

        @Schema(title = "Sets the default charset to use.")
        @Builder.Default
        @PluginProperty(dynamic = false)
        private final Charset defaultCharset = StandardCharsets.UTF_8;

        @Schema(title = "Whether redirects should be followed.")
        @Builder.Default
        @PluginProperty(dynamic = false)
        private final Boolean followRedirects = HttpClientConfiguration.DEFAULT_FOLLOW_REDIRECTS;

        @Schema(title = "The level to enable trace logging at.")
        @PluginProperty(dynamic = false)
        private final LogLevel logLevel;

        @Schema(title = "The basicAuth username.")
        @PluginProperty(dynamic = true)
        private final String basicAuthUser;

        @Schema(title = "The basicAuth password.")
        @PluginProperty(dynamic = true)
        private final String basicAuthPassword;
    }

    @Getter
    @Builder
    public static class SslOptions {
        @Schema(
            title = "Whether the client should disable checking of the remote SSL certificate.",
            description = "Only applies if no trust store is configured. Note: This makes the SSL connection insecure, and should only be used for testing. If you are using a self-signed certificate, set up a trust store instead."
        )
        @PluginProperty(dynamic = false)
        private final Boolean insecureTrustAllCertificates;
    }
}
