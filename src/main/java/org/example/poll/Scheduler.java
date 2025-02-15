package org.example.poll;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.example.Constants;
import org.example.database.QueryUtility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Scheduler extends AbstractVerticle
{
    private static final Logger logger = LoggerFactory.getLogger(Scheduler.class);

    private final Map<Long,JsonObject> pollDevices = new HashMap<>(); //Will contain provisioned devices

    public void start()
    {
        try
        {
            //If device is provisioned after I have fetched provisioned devices from database
            vertx.eventBus().<JsonObject>localConsumer(Constants.OBJECT_PROVISION, object->
            {
                var objectID = object.body().getLong("object_id");

                fetchMetricData(objectID)
                        .onSuccess(metrics ->
                        {
                            var deviceMetrics = new JsonObject()
                                    .put("device", object.body())
                                    .put("metrics", metrics);

                            pollDevices.put(objectID, deviceMetrics);
                        })
                        .onFailure(err -> logger.error("Failed to fetch {}: {}", objectID, err.getMessage()));
            });

            //Will fetch provisioned devices from database as soon as this verticle deploys
            getDevices()
                    .onComplete(v->
                            vertx.setPeriodic(Constants.PERIODIC_INTERVAL,id-> checkAndPreparePolling()));
        }
        catch (Exception exception)
        {
            logger.error(exception.getMessage(),exception);
        }
    }

    //To fetch provisioned devices present in database
    private Future<Void> getDevices()
    {
        var promise = Promise.<Void>promise();

        QueryUtility.getInstance().getAll(Constants.OBJECTS)
                .onSuccess(objectsArray->
                {
                    logger.info("Fetched {} rows from 'objects' table", objectsArray.size());

                    for (int i = 0; i < objectsArray.size(); i++)
                    {
                        // Object will contain information like object_id, credential_profile, ip, hostname and device_type
                        var object = objectsArray.getJsonObject(i);

                        var objectId = object.getLong("object_id");

                        fetchMetricData(objectId)
                                .onSuccess(metrics ->
                                {
                                    var deviceMetrics = new JsonObject()
                                            .put("device", object)
                                            .put("metrics", metrics);

                                    pollDevices.put(objectId, deviceMetrics);
                                })
                                .onFailure(err -> logger.error("Failed to fetch metrics {}: {}", objectId, err.getMessage()));
                    }

                    promise.complete();
                })
                .onFailure(error->
                {
                    promise.fail(error.getMessage());
                });

        return promise.future();
    }

    private Future<JsonArray> fetchMetricData(Long objectId)
    {
        var promise = Promise.<JsonArray>promise();
        // Fetch all rows from the 'metric_object' table for the given object_id

        var metricColumns = List.of("metric_group_name", "metric_poll_time", "last_polled");

        QueryUtility.getInstance().get(Constants.METRICS,metricColumns,new JsonObject().put("metric_object",objectId))
                .onSuccess(metric->
                {
                    var metricArray = metric.getJsonArray("data");

                    promise.complete(metricArray);
                })
                .onFailure(error ->
                {
                    logger.error("Failed to fetch metrics for object {}: {}", objectId, error.getMessage());

                    promise.fail(error.getMessage());
                });

        return promise.future();
    }

    private void checkAndPreparePolling()
    {
        if(pollDevices.isEmpty())
        {
            logger.info("No provisioned devices currently");

            return;
        }
        for (Map.Entry<Long, JsonObject> entry : pollDevices.entrySet())
        {
            var deviceMetrics = entry.getValue();

            var device = deviceMetrics.getJsonObject("device");

            var metrics = deviceMetrics.getJsonArray("metrics");

            for (int i = 0; i < metrics.size(); i++)
            {
                var metricData = metrics.getJsonObject(i);

                preparePolling(device, metricData);
            }
        }
    }

    private void preparePolling(JsonObject objectData, JsonObject metricData)
    {
        try
        {
            var pollTime = metricData.getInteger("metric_poll_time") * 1000L; // To convert to milliseconds

            var lastPolled = metricData.getString("last_polled") != null
                    ? LocalDateTime.parse(metricData.getString("last_polled"), DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    : null;

            var currentMillis = System.currentTimeMillis();

            if (lastPolled == null || currentMillis - lastPolled >= pollTime)
            {
                vertx.eventBus().send(Constants.OBJECT_POLL, new JsonObject()
                        .put("credential.profile", objectData.getLong("credential_profile"))
                        .put("ip", objectData.getString("ip"))
                                .put("device_type",objectData.getString("device_type"))
                        .put("metric.group.name", metricData.getString("metric_group_name"))
                        .put("timestamp",currentMillis/1000));

                logger.info("Polling triggered for {} at {}", objectData.getString("hostname"), objectData.getString("ip"));

                // Update the last polled time in the hashmap

                var currentTime = LocalDateTime.now();

                metricData.put("last_polled", currentTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")));

                // Update metric group name which got polled and last polled time in the database

                QueryUtility.getInstance().update(Constants.METRICS, new JsonObject().put("last_polled", currentTime),
                                        new JsonObject().put("metric_group_name",metricData.getString("metric_group_name")).put("metric_object", objectData.getLong("object_id")))
                        .onSuccess(updated ->
                        {
                            if (updated)
                            {
                                logger.info("Last polled time updated for object ID : {}", objectData.getLong("object_id"));
                            }
                        })
                        .onFailure(error ->
                        {
                            logger.error("Failed to update last polled time for object ID : {} - {}", objectData.getLong("object_id"), error.getMessage());
                        });
            }
            else
            {
                logger.info("Finding qualified devices to proceed for polling");
            }
        }
        catch (Exception exception)
        {
            logger.error(exception.getMessage(),exception);
        }
    }
}
