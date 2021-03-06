package com.linbit.linstor.api.rest.v1.config;

import com.linbit.InvalidNameException;
import com.linbit.ServiceName;
import com.linbit.SystemService;
import com.linbit.SystemServiceStartException;
import com.linbit.linstor.api.ApiCallRcImpl;
import com.linbit.linstor.api.ApiConsts;
import com.linbit.linstor.api.rest.v1.utils.ApiCallRcRestUtils;
import com.linbit.linstor.core.apicallhandler.response.ApiRcException;
import com.linbit.linstor.core.cfg.CtrlConfig;
import com.linbit.linstor.core.cfg.LinstorConfig;
import com.linbit.linstor.core.cfg.LinstorConfig.RestAccessLogMode;
import com.linbit.linstor.logging.ErrorReporter;

import javax.ws.rs.NotFoundException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.UriInfo;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;
import java.io.IOException;
import java.net.SocketException;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.inject.Injector;
import org.glassfish.grizzly.http.CompressionConfig;
import org.glassfish.grizzly.http.server.HttpHandler;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.grizzly.http.server.NetworkListener;
import org.glassfish.grizzly.http.server.Request;
import org.glassfish.grizzly.http.server.Response;
import org.glassfish.grizzly.http.server.accesslog.AccessLogBuilder;
import org.glassfish.grizzly.http.util.HttpStatus;
import org.glassfish.grizzly.ssl.SSLContextConfigurator;
import org.glassfish.grizzly.ssl.SSLEngineConfigurator;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;
import org.glassfish.jersey.server.ResourceConfig;

public class GrizzlyHttpService implements SystemService
{
    private final ErrorReporter errorReporter;
    private HttpServer httpServer;
    private HttpServer httpsServer;
    private ServiceName instanceName;
    private final String listenAddress;
    private final String listenAddressSecure;
    private final Path keyStoreFile;
    private final String keyStorePassword;
    private final Path trustStoreFile;
    private final String trustStorePassword;
    private final ResourceConfig restResourceConfig;
    private final Path restAccessLogPath;
    private RestAccessLogMode restAccessLogMode;

    private static final int COMPRESSION_MIN_SIZE = 1000; // didn't find a good default, so lets say 1000

    public GrizzlyHttpService(
        Injector injector,
        ErrorReporter errorReporterRef,
        String listenAddressRef,
        String listenAddressSecureRef,
        Path keyStoreFileRef,
        String keyStorePasswordRef,
        Path trustStoreFileRef,
        String trustStorePasswordRef,
        String restAccessLogPathRef,
        CtrlConfig.RestAccessLogMode restAccessLogModeRef
    )
    {
        errorReporter = errorReporterRef;
        listenAddress = listenAddressRef;
        listenAddressSecure = listenAddressSecureRef;
        keyStoreFile = keyStoreFileRef;
        keyStorePassword = keyStorePasswordRef;
        trustStoreFile = trustStoreFileRef;
        trustStorePassword = trustStorePasswordRef;
        restAccessLogPath = Paths.get(restAccessLogPathRef);
        restAccessLogMode = restAccessLogModeRef;
        restResourceConfig = new GuiceResourceConfig(injector).packages("com.linbit.linstor.api.rest");
        restResourceConfig.register(new CORSFilter());
        registerExceptionMappers(restResourceConfig);

        try
        {
            instanceName = new ServiceName("GrizzlyHttpServer");
        }
        catch (InvalidNameException ignored)
        {
        }
    }

    private void addHTTPSRedirectHandler(HttpServer httpServerRef, int httpsPort)
    {
        httpServerRef.getServerConfiguration().addHttpHandler(
            new HttpHandler()
            {
                @Override
                public void service(Request request, Response response) throws Exception
                {
                    response.setStatus(HttpStatus.NOT_FOUND_404);
                    response.sendRedirect(
                        String.format("https://%s:%d", request.getServerName(), httpsPort) +
                            request.getHttpHandlerPath()
                    );
                }
            }
        );
    }

    private void enableCompression(HttpServer httpServerRef)
    {
        CompressionConfig compressionConfig = httpServerRef.getListener("grizzly").getCompressionConfig();
        compressionConfig.setCompressionMode(CompressionConfig.CompressionMode.ON);
        compressionConfig.setCompressibleMimeTypes("text/plain", "text/html", "application/json");
        compressionConfig.setCompressionMinSize(COMPRESSION_MIN_SIZE);
    }

    private void initGrizzly(final String bindAddress, final String httpsBindAddress)
    {
        if (keyStoreFile != null)
        {
            final URI httpsUri = URI.create(String.format("https://%s", httpsBindAddress));

            // only install a redirect handler for http
            httpServer = GrizzlyHttpServerFactory.createHttpServer(
                URI.create(String.format("http://%s", bindAddress)),
                false
            );

            addHTTPSRedirectHandler(httpServer, httpsUri.getPort());

            httpsServer = GrizzlyHttpServerFactory.createHttpServer(
                httpsUri,
                restResourceConfig,
                false
            );

            SSLContextConfigurator sslCon = new SSLContextConfigurator();
            sslCon.setSecurityProtocol("TLS");
            sslCon.setKeyStoreFile(keyStoreFile.toString());
            sslCon.setKeyStorePass(keyStorePassword);

            boolean hasClientAuth = trustStoreFile != null;
            if (hasClientAuth)
            {
                sslCon.setTrustStoreFile(trustStoreFile.toString());
                sslCon.setTrustStorePass(trustStorePassword);
            }

            for (NetworkListener netListener : httpsServer.getListeners())
            {
                netListener.setSecure(true);
                SSLEngineConfigurator ssle = new SSLEngineConfigurator(sslCon);
                ssle.setWantClientAuth(hasClientAuth);
                ssle.setClientMode(false);
                ssle.setNeedClientAuth(hasClientAuth);
                netListener.setSSLEngineConfig(ssle);
            }

            enableCompression(httpsServer);
        }
        else
        {
            httpsServer = null;
            httpServer = GrizzlyHttpServerFactory.createHttpServer(
                URI.create(String.format("http://%s", bindAddress)),
                restResourceConfig,
                false
            );
        }

        // configure access logging
        if (restAccessLogMode == null)
        {
            errorReporter.logWarning("Unknown rest_access_log_mode set, fallback to append");
            restAccessLogMode = LinstorConfig.RestAccessLogMode.APPEND;
        }

        if (restAccessLogMode != LinstorConfig.RestAccessLogMode.NO_LOG)
        {
            final Path accessLogPath = restAccessLogPath.isAbsolute() ?
                restAccessLogPath : errorReporter.getLogDirectory().resolve(restAccessLogPath);
            final AccessLogBuilder builder = new AccessLogBuilder(accessLogPath.toFile());

            switch (restAccessLogMode)
            {
                case ROTATE_HOURLY:
                    errorReporter.logDebug("Rest-access log set to rotate hourly.");
                    builder.rotatedHourly();
                    break;
                case ROTATE_DAILY:
                    errorReporter.logDebug("Rest-access log set to rotate daily.");
                    builder.rotatedDaily();
                    break;
                case APPEND:
                case NO_LOG:
                default:
            }

            if (httpServer != null)
            {
                builder.instrument(httpServer.getServerConfiguration());
            }
            if (httpsServer != null)
            {
                builder.instrument(httpsServer.getServerConfiguration());
            }
        }
        else
        {
            errorReporter.logDebug("Rest-access log turned off.");
        }

        if (httpServer != null)
        {
            enableCompression(httpServer);
        }
    }

    private void registerExceptionMappers(ResourceConfig resourceConfig)
    {
        resourceConfig.register(new LinstorMapper(errorReporter));
    }

    @Override
    public void setServiceInstanceName(ServiceName instanceNameRef)
    {
        instanceName = instanceNameRef;
    }

    @Override
    public void start() throws SystemServiceStartException
    {
        try
        {
            initGrizzly(listenAddress, listenAddressSecure);
            httpServer.start();

            if (httpsServer != null)
            {
                httpsServer.start();
            }
        }
        catch (SocketException sexc)
        {
            errorReporter.logError("Unable to start grizzly http server on " + listenAddress + ".");
            // ipv6 failed, if it is localhost ipv6, retry ipv4
            if (listenAddress.startsWith("[::]"))
            {
                URI uri = URI.create(String.format("http://%s", listenAddress));
                URI uriSecure = URI.create(String.format("https://%s", listenAddressSecure));
                errorReporter.logInfo("Trying to start grizzly http server on fallback ipv4: 0.0.0.0");
                try
                {
                    initGrizzly("0.0.0.0:" + uri.getPort(), "0.0.0.0:" + uriSecure.getPort());
                    httpServer.start();

                    if (httpsServer != null)
                    {
                        httpsServer.start();
                    }
                }
                catch (IOException exc)
                {
                    throw new SystemServiceStartException(
                        "Unable to start grizzly http server on fallback ipv4",
                        exc,
                        true
                    );
                }
            }
        }
        catch (IOException exc)
        {
            throw new SystemServiceStartException("Unable to start grizzly http server", exc, true);
        }
    }

    @Override
    public void shutdown()
    {
        httpServer.shutdownNow();
        if (httpsServer != null)
        {
            httpsServer.shutdownNow();
        }
    }

    @Override
    public void awaitShutdown(long timeout)
    {
        httpServer.shutdown(timeout, TimeUnit.SECONDS);
        if (httpsServer != null)
        {
            httpsServer.shutdown(timeout, TimeUnit.SECONDS);
        }
    }

    @Override
    public ServiceName getServiceName()
    {
        ServiceName svcName = null;
        try
        {
            svcName = new ServiceName("Grizzly-HTTP-Server");
        }
        catch (InvalidNameException ignored)
        {
        }
        return svcName;
    }

    @Override
    public String getServiceInfo()
    {
        return "Grizzly HTTP server";
    }

    @Override
    public ServiceName getInstanceName()
    {
        return instanceName;
    }

    @Override
    public boolean isStarted()
    {
        return httpServer.isStarted();
    }
}

@Provider
class LinstorMapper implements ExceptionMapper<Exception>
{
    private final ErrorReporter errorReporter;

    @Context
    private UriInfo uriInfo;

    LinstorMapper(
        ErrorReporter errorReporterRef
    )
    {
        errorReporter = errorReporterRef;
    }

    @Override
    public javax.ws.rs.core.Response toResponse(Exception exc)
    {
        String errorReport = errorReporter.reportError(exc);
        javax.ws.rs.core.Response.Status respStatus;

        ApiCallRcImpl apiCallRc = new ApiCallRcImpl();
        if (exc instanceof ApiRcException)
        {
            apiCallRc.addEntries(((ApiRcException) exc).getApiCallRc());
            respStatus = javax.ws.rs.core.Response.Status.BAD_REQUEST;
        }
        else
        if (exc instanceof JsonMappingException ||
            exc instanceof JsonParseException)
        {
            apiCallRc.addEntry(
                ApiCallRcImpl.entryBuilder(
                    ApiConsts.API_CALL_PARSE_ERROR,
                    "Unable to parse input json."
                )
                    .setDetails(exc.getMessage())
                    .addErrorId(errorReport)
                    .build()
            );
            respStatus = javax.ws.rs.core.Response.Status.INTERNAL_SERVER_ERROR;
        }
        else
        if (exc instanceof NotFoundException)
        {
            apiCallRc.addEntry(
                ApiCallRcImpl.entryBuilder(
                    ApiConsts.FAIL_UNKNOWN_ERROR,
                    String.format("Path '%s' not found on server.", uriInfo.getPath())
                ).setDetails(exc.getMessage()).build());
            respStatus = javax.ws.rs.core.Response.Status.NOT_FOUND;
        }
        else
        {
            apiCallRc.addEntry(
                ApiCallRcImpl.entryBuilder(
                    ApiConsts.FAIL_UNKNOWN_ERROR,
                    "An unknown error occurred."
                )
                    .setDetails(exc.getMessage())
                    .addErrorId(errorReport)
                    .build()
            );
            respStatus = javax.ws.rs.core.Response.Status.INTERNAL_SERVER_ERROR;
        }

        return javax.ws.rs.core.Response
            .status(respStatus)
            .type(MediaType.APPLICATION_JSON)
            .entity(ApiCallRcRestUtils.toJSON(apiCallRc))
            .build();
    }
}
