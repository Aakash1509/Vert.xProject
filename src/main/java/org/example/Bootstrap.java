package org.example;

import io.vertx.core.Vertx;
import io.vertx.core.impl.logging.Logger;
import io.vertx.core.impl.logging.LoggerFactory;
import org.example.routes.Server;

public class Bootstrap
{
    public static final Vertx vertx = Vertx.vertx();

    private static final Logger logger = LoggerFactory.getLogger(Bootstrap.class);

    public static void main(String[] args)
    {
        vertx.deployVerticle(new Server())
                .onComplete(result-> {
                   if(result.succeeded())
                   {
                       logger.info("Server verticle deployed successfully");
                   }
                   else
                   {
                       logger.error("Error in discovery routing", result.cause());
                   }
                });
    }
}