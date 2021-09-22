package br.com.merkandi.rest;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.SupplierUtils;
import io.vavr.control.Try;

import org.glassfish.jersey.client.ClientProperties;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.time.Duration;
import java.util.function.Supplier;
import java.util.logging.FileHandler;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

@Path("a")
@WebListener
public class AService implements ServletContextListener
{
    private static CircuitBreaker circuitBreaker = null;
    private static FileHandler aServiceLoggerFilehandler;
    private static Logger aServiceLogger = Logger.getLogger("AServiceLogger");
    private static Long REQUEST_COUNTER = 0L;
    private static String B_API_PATH = null;


    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public Response getA()
    {
        REQUEST_COUNTER++;

        String bResponseText = null;

        Supplier<Response> supplier = () -> getAExecute();

        supplier = SupplierUtils.andThen(supplier, result ->
        {
            if (result.getStatus() >= 400)
            {
                aServiceLogger.info("Runtime Error. HTTP Return Code: " + result.getStatus());
                throw new RuntimeException();
            }
            return result;
        });

        supplier = CircuitBreaker.decorateSupplier(circuitBreaker, supplier);

        try
        {
            Response bResponse = Try.ofSupplier(supplier).get();
            bResponseText = bResponse.readEntity(String.class);
        }
        catch (Exception e)
        {
            bResponseText = getAlternativeResponse();
        }

        aServiceLogger.info("AService response content: A_" + bResponseText + "\n\n");

        return Response.ok().entity("A_" + bResponseText).build();
    }


    public Response getAExecute()
    {
        Client client = ClientBuilder.newClient();
        client.property(ClientProperties.CONNECT_TIMEOUT, 500);
        client.property(ClientProperties.READ_TIMEOUT,    2500);
        WebTarget webTarget = client.target(B_API_PATH);
        Invocation.Builder invocationBuilder = webTarget.request(MediaType.TEXT_PLAIN);

        return invocationBuilder.get();
    }


    private String getAlternativeResponse()
    {
        String alternativeResult = ">>b";
        return alternativeResult;
    }

    @GET
    @Path("/count")
    @Produces(MediaType.TEXT_PLAIN)
    public Response getRequestCount()
    {
        return Response.ok().entity(REQUEST_COUNTER).build();
    }

    @Override
    public void contextInitialized(ServletContextEvent sce)
    {
        B_API_PATH = sce.getServletContext().getInitParameter("B_API_PATH");

        try
        {
            SimpleFormatter formatter = new SimpleFormatter();
            aServiceLoggerFilehandler = new FileHandler( sce.getServletContext().getInitParameter("A_LOG_PATH") );
            aServiceLoggerFilehandler.setFormatter(formatter);
            aServiceLogger.addHandler(aServiceLoggerFilehandler);
        }
        catch (SecurityException e)
        {
            e.printStackTrace();
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }

        if( circuitBreaker == null )
        {
            CircuitBreakerConfig circuitBreakerConfig = CircuitBreakerConfig.custom()
                    .failureRateThreshold(50)
                    .slidingWindowSize(5)
                    .waitDurationInOpenState(Duration.ofSeconds(10))
                    .permittedNumberOfCallsInHalfOpenState(5)
                    .slowCallDurationThreshold(Duration.ofSeconds(3))
                    .slowCallRateThreshold(50)
                    .build();
            CircuitBreakerRegistry circuitBreakerRegistry = CircuitBreakerRegistry.of(circuitBreakerConfig);
            circuitBreaker = circuitBreakerRegistry.circuitBreaker("circuit-breaker-2-b");
            circuitBreaker.getEventPublisher().onEvent(event -> aServiceLogger.info(event.getCircuitBreakerName() + " | " + event.getEventType() + " | Current State:" + circuitBreaker.getState().name()) );
        }

        aServiceLogger.info("\nAService running...");
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {

    }
}