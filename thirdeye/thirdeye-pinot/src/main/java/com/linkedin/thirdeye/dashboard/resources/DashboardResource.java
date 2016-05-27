package com.linkedin.thirdeye.dashboard.resources;

import io.dropwizard.views.View;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;

import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;

import org.apache.commons.io.IOUtils;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.google.common.cache.CacheLoader.InvalidCacheLoadException;
import com.linkedin.thirdeye.api.CollectionSchema;
import com.linkedin.thirdeye.api.TimeGranularity;
import com.linkedin.thirdeye.client.MetricExpression;
import com.linkedin.thirdeye.client.ThirdEyeCacheRegistry;
import com.linkedin.thirdeye.client.cache.QueryCache;
import com.linkedin.thirdeye.client.timeseries.TimeSeriesHandler;
import com.linkedin.thirdeye.client.timeseries.TimeSeriesRequest;
import com.linkedin.thirdeye.client.timeseries.TimeSeriesResponse;
import com.linkedin.thirdeye.client.timeseries.TimeSeriesRow;
import com.linkedin.thirdeye.client.timeseries.TimeSeriesRow.TimeSeriesMetric;
import com.linkedin.thirdeye.dashboard.Utils;
import com.linkedin.thirdeye.dashboard.configs.AbstractConfigDAO;
import com.linkedin.thirdeye.dashboard.configs.CollectionConfig;
import com.linkedin.thirdeye.dashboard.configs.DashboardConfig;
import com.linkedin.thirdeye.dashboard.views.DashboardView;
import com.linkedin.thirdeye.dashboard.views.ViewHandler;
import com.linkedin.thirdeye.dashboard.views.ViewRequest;
import com.linkedin.thirdeye.dashboard.views.ViewResponse;
import com.linkedin.thirdeye.dashboard.views.contributor.ContributorViewHandler;
import com.linkedin.thirdeye.dashboard.views.contributor.ContributorViewRequest;
import com.linkedin.thirdeye.dashboard.views.contributor.ContributorViewResponse;
import com.linkedin.thirdeye.dashboard.views.heatmap.HeatMapViewHandler;
import com.linkedin.thirdeye.dashboard.views.heatmap.HeatMapViewRequest;
import com.linkedin.thirdeye.dashboard.views.heatmap.HeatMapViewResponse;
import com.linkedin.thirdeye.dashboard.views.tabular.TabularViewHandler;
import com.linkedin.thirdeye.dashboard.views.tabular.TabularViewRequest;
import com.linkedin.thirdeye.dashboard.views.tabular.TabularViewResponse;
import com.linkedin.thirdeye.util.ThirdEyeUtils;

@Path(value = "/dashboard")
// @Produces(MediaType.APPLICATION_JSON)
public class DashboardResource {
  private static final ThirdEyeCacheRegistry CACHE_REGISTRY_INSTANCE = ThirdEyeCacheRegistry
      .getInstance();
  private static final Logger LOG = LoggerFactory.getLogger(DashboardResource.class);
  private static final String DEFAULT_TIMEZONE_ID = "UTC";
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static String DEFAULT_DASHBOARD = "Default_Dashboard";
  private static String COUNT_METRIC = "__COUNT";

  private QueryCache queryCache;
  private AbstractConfigDAO<DashboardConfig> dashboardConfigDAO;

  public DashboardResource() {
  }

  public DashboardResource(AbstractConfigDAO<DashboardConfig> dashboardConfigDAO) {
    this.queryCache = CACHE_REGISTRY_INSTANCE.getQueryCache();
    this.dashboardConfigDAO = dashboardConfigDAO;
  }

  @GET
  @Path(value = "/")
  @Produces(MediaType.TEXT_HTML)
  public View getDashboardView() {
    return new DashboardView();
  }

  @GET
  @Path(value = "/data/datasets")
  @Produces(MediaType.APPLICATION_JSON)
  public String getCollections() {
    String jsonCollections = null;
    try {
      List<String> collections = queryCache.getClient().getCollections();
      jsonCollections = OBJECT_MAPPER.writeValueAsString(collections);
    } catch (Exception e) {
      LOG.error("Error while fetching datasets", e);
    }

    return jsonCollections;
  }

  @GET
  @Path(value = "/data/metrics")
  @Produces(MediaType.APPLICATION_JSON)
  public String getMetrics(@QueryParam("dataset") String collection) {
    String jsonMetrics = null;
    try {
      CollectionSchema schema = CACHE_REGISTRY_INSTANCE.getCollectionSchemaCache().get(collection);
      List<String> metrics = schema.getMetricNames();
      CollectionConfig collectionConfig = null;
      try {
        collectionConfig = queryCache.getClient().getCollectionConfig(collection);
      } catch (InvalidCacheLoadException e) {
        LOG.debug("No collection configs for collection {}", collection);
      }
      if (collectionConfig != null && collectionConfig.getDerivedMetrics() != null) {
        metrics.addAll(collectionConfig.getDerivedMetrics().keySet());
        metrics.removeAll(collectionConfig.getDerivedMetrics().values());
      }
      Collections.sort(metrics);
      jsonMetrics = OBJECT_MAPPER.writeValueAsString(metrics);
    } catch (Exception e) {
      LOG.error("Error while fetching metrics", e);
    }

    return jsonMetrics;
  }

  @GET
  @Path(value = "/data/dimensions")
  @Produces(MediaType.APPLICATION_JSON)
  public String getDimensions(@QueryParam("dataset") String collection) {
    String jsonDimensions = null;
    try {
      // CollectionSchema schema = queryCache.getClient().getCollectionSchema(collection);
      // List<String> dimensions = schema.getDimensionNames();

      // Collections.sort(dimensions);
      List<String> dimensions = Utils.getDimensions(queryCache, collection);
      jsonDimensions = OBJECT_MAPPER.writeValueAsString(dimensions);
    } catch (Exception e) {
      LOG.error("Error while fetching dimensions for collection: " + collection, e);
    }

    return jsonDimensions;
  }

  @GET
  @Path(value = "/data/dashboards")
  @Produces(MediaType.APPLICATION_JSON)
  public String getDashboards(@QueryParam("dataset") String collection) {
    String jsonDashboards = null;
    try {
      jsonDashboards = CACHE_REGISTRY_INSTANCE.getDashboardsCache().get(collection);
    } catch (Exception e) {
      LOG.error("Error while fetching dashboards for collection: " + collection, e);
    }
    return jsonDashboards;
  }

  @GET
  @Path(value = "/data/info")
  @Produces(MediaType.APPLICATION_JSON)
  public String getMaxTime(@QueryParam("dataset") String collection) {
    String collectionInfo = null;
    try {
      HashMap<String, String> map = new HashMap<>();
      long maxDataTime = CACHE_REGISTRY_INSTANCE.getCollectionMaxDataTimeCache().get(collection);
      CollectionSchema collectionSchema =
          CACHE_REGISTRY_INSTANCE.getCollectionSchemaCache().get(collection);
      TimeGranularity dataGranularity = collectionSchema.getTime().getDataGranularity();
      map.put("maxTime", "" + maxDataTime);
      map.put("dataGranularity", dataGranularity.getUnit().toString());
      collectionInfo = OBJECT_MAPPER.writeValueAsString(map);
    } catch (Exception e) {
      LOG.error("Error while fetching info for collection: " + collection, e);
    }
    return collectionInfo;
  }

  @GET
  @Path(value = "/data/filters")
  @Produces(MediaType.APPLICATION_JSON)
  public String getFilters(@QueryParam("dataset") String collection,
      @QueryParam("start") String start, @QueryParam("end") String end) {
    String jsonFilters = null;
    try {
      jsonFilters = CACHE_REGISTRY_INSTANCE.getDimensionFiltersCache().get(collection);
    } catch (ExecutionException e) {
      LOG.error("Exception while getting filters for collection {}", collection, e);
    }
    return jsonFilters;
  }

  @GET
  @Path(value = "/data/customDashboard")
  @Produces(MediaType.APPLICATION_JSON)
  public String getDashboardData(@QueryParam("dataset") String collection,
      @QueryParam("dashboard") String dashboardName, @QueryParam("filters") String filterJson,
      @QueryParam("timeZone") @DefaultValue(DEFAULT_TIMEZONE_ID) String timeZone,
      @QueryParam("baselineStart") Long baselineStart, @QueryParam("baselineEnd") Long baselineEnd,
      @QueryParam("currentStart") Long currentStart, @QueryParam("currentEnd") Long currentEnd,
      @QueryParam("compareMode") String compareMode,
      @QueryParam("aggTimeGranularity") String aggTimeGranularity) {
    try {
      TabularViewRequest request = new TabularViewRequest();
      request.setCollection(collection);
      DashboardConfig dashboardConfig;
      List<MetricExpression> metricExpressions;
      if (dashboardName == null || DEFAULT_DASHBOARD.equals(dashboardName)) {
        CollectionConfig collectionConfig = null;
        try {
          collectionConfig = CACHE_REGISTRY_INSTANCE.getCollectionConfigCache().get(collection);
        } catch (InvalidCacheLoadException e) {
          LOG.debug("No collection configs for collection {}", collection);
        }
        CollectionSchema collectionSchema = CACHE_REGISTRY_INSTANCE.getCollectionSchemaCache().get(collection);

        metricExpressions = new ArrayList<>();
        List<String> metricNames = collectionSchema.getMetricNames();
        for (String metric : metricNames) {
          if (metric.equals(COUNT_METRIC) && (collectionConfig == null || !collectionConfig.isEnableCount())) {
            continue;
          }
          metricExpressions.add(new MetricExpression(metric));
        }
      } else {
        dashboardConfig = dashboardConfigDAO.findById(collection + "_" + dashboardName);
        metricExpressions = dashboardConfig.getMetricExpressions();
      }
      request.setMetricExpressions(metricExpressions);
      long maxDataTime = CACHE_REGISTRY_INSTANCE.getCollectionMaxDataTimeCache().get(collection);
      if (currentEnd > maxDataTime) {
        long delta = currentEnd - maxDataTime;
        currentEnd = currentEnd - delta;
        baselineEnd = baselineEnd - delta;
      }
      request.setBaselineStart(new DateTime(baselineStart, DateTimeZone.forID(timeZone)));
      request.setBaselineEnd(new DateTime(baselineEnd, DateTimeZone.forID(timeZone)));
      request.setCurrentStart(new DateTime(currentStart, DateTimeZone.forID(timeZone)));
      request.setCurrentEnd(new DateTime(currentEnd, DateTimeZone.forID(timeZone)));
      if (filterJson != null && !filterJson.isEmpty()) {
        filterJson = URLDecoder.decode(filterJson, "UTF-8");
        request.setFilters(ThirdEyeUtils.convertToMultiMap(filterJson));
      }

      request.setTimeGranularity(Utils.getAggregationTimeGranularity(aggTimeGranularity));

      TabularViewHandler handler = new TabularViewHandler(queryCache);
      String jsonResponse = null;

      TabularViewResponse response = handler.process(request);
      jsonResponse =
          OBJECT_MAPPER.enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(response);
      LOG.debug("customDashboard response {}", jsonResponse);
      return jsonResponse;
    } catch (Exception e) {
      LOG.error("Exception while processing /data/tabular call", e);
      return "{\"ERROR\": + " + e.getMessage() + "}";
    }

  }

  @GET
  @Path(value = "/data/viewData")
  @Produces(MediaType.APPLICATION_JSON)
  public String getDashboard(@QueryParam("dataset") String collection,
      @QueryParam("viewType") String viewType, @QueryParam("viewType") String viewRequestParamsJson) {
    try {
      ViewHandler<ViewRequest, ViewResponse> viewHandler = getViewHandler(viewType);
      ViewRequestParams viewRequesParams = ViewRequestParams.fromJson(viewRequestParamsJson);
      ViewRequest viewRequest = viewHandler.createRequest(viewRequesParams);

      ViewResponse response = viewHandler.process(viewRequest);
      String jsonResponse = OBJECT_MAPPER.writer().writeValueAsString(response);
      return jsonResponse;
    } catch (Exception e) {
      e.printStackTrace();
    }
    return null;
  }

  private ViewHandler<ViewRequest, ViewResponse> getViewHandler(String viewType) {
    // TODO Auto-generated method stub
    return null;
  }

  @GET
  @Path(value = "/data/heatmap")
  @Produces(MediaType.APPLICATION_JSON)
  public String getHeatMap(@QueryParam("dataset") String collection,
      @QueryParam("filters") String filterJson,
      @QueryParam("timeZone") @DefaultValue(DEFAULT_TIMEZONE_ID) String timeZone,
      @QueryParam("baselineStart") Long baselineStart, @QueryParam("baselineEnd") Long baselineEnd,
      @QueryParam("currentStart") Long currentStart, @QueryParam("currentEnd") Long currentEnd,
      @QueryParam("compareMode") String compareMode, @QueryParam("metrics") String metricsJson)
      throws Exception {

    HeatMapViewRequest request = new HeatMapViewRequest();

    request.setCollection(collection);
    List<MetricExpression> metricExpressions =
        Utils.convertToMetricExpressions(metricsJson, collection);
    request.setMetricExpressions(metricExpressions);
    long maxDataTime = CACHE_REGISTRY_INSTANCE.getCollectionMaxDataTimeCache().get(collection);
    if (currentEnd > maxDataTime) {
      long delta = currentEnd - maxDataTime;
      currentEnd = currentEnd - delta;
      baselineEnd = baselineEnd - delta;
    }
    request.setBaselineStart(new DateTime(baselineStart, DateTimeZone.forID(timeZone)));
    request.setBaselineEnd(new DateTime(baselineEnd, DateTimeZone.forID(timeZone)));
    request.setCurrentStart(new DateTime(currentStart, DateTimeZone.forID(timeZone)));
    request.setCurrentEnd(new DateTime(currentEnd, DateTimeZone.forID(timeZone)));
    // filter
    if (filterJson != null && !filterJson.isEmpty()) {
      filterJson = URLDecoder.decode(filterJson, "UTF-8");
      request.setFilters(ThirdEyeUtils.convertToMultiMap(filterJson));
    }

    HeatMapViewHandler handler = new HeatMapViewHandler(queryCache);
    HeatMapViewResponse response;
    String jsonResponse = null;

    try {
      response = handler.process(request);
      jsonResponse = OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(response);
      LOG.debug("Heatmap response {}", jsonResponse);
    } catch (Exception e) {
      LOG.error("Error generating heatmap response", e);
    }

    return jsonResponse;
  }

  @GET
  @Path(value = "/data/tabular")
  @Produces(MediaType.APPLICATION_JSON)
  public String getTabularData(@QueryParam("dataset") String collection,
      @QueryParam("filterClause") String filterJson,
      @QueryParam("timeZone") @DefaultValue(DEFAULT_TIMEZONE_ID) String timeZone,
      @QueryParam("baselineStart") Long baselineStart, @QueryParam("baselineEnd") Long baselineEnd,
      @QueryParam("currentStart") Long currentStart, @QueryParam("currentEnd") Long currentEnd,
      @QueryParam("compareMode") String compareMode,
      @QueryParam("aggTimeGranularity") String aggTimeGranularity,
      @QueryParam("metrics") String metricsJson) throws Exception {
    TabularViewRequest request = new TabularViewRequest();
    request.setCollection(collection);

    List<MetricExpression> metricExpressions =
        Utils.convertToMetricExpressions(metricsJson, collection);
    request.setMetricExpressions(metricExpressions);
    long maxDataTime = CACHE_REGISTRY_INSTANCE.getCollectionMaxDataTimeCache().get(collection);
    if (currentEnd > maxDataTime) {
      long delta = currentEnd - maxDataTime;
      currentEnd = currentEnd - delta;
      baselineEnd = baselineEnd - delta;
    }

    request.setBaselineStart(new DateTime(baselineStart, DateTimeZone.forID(timeZone)));
    request.setBaselineEnd(new DateTime(baselineEnd, DateTimeZone.forID(timeZone)));
    request.setCurrentStart(new DateTime(currentStart, DateTimeZone.forID(timeZone)));
    request.setCurrentEnd(new DateTime(currentEnd, DateTimeZone.forID(timeZone)));
    if (filterJson != null && !filterJson.isEmpty()) {
      filterJson = URLDecoder.decode(filterJson, "UTF-8");
      request.setFilters(ThirdEyeUtils.convertToMultiMap(filterJson));
    }
    request.setTimeGranularity(Utils.getAggregationTimeGranularity(aggTimeGranularity));

    TabularViewHandler handler = new TabularViewHandler(queryCache);
    String jsonResponse = null;

    try {
      TabularViewResponse response = handler.process(request);
      jsonResponse = OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(response);
      LOG.debug("Tabular response {}", jsonResponse);
    } catch (Exception e) {
      LOG.error("Exception while processing /data/tabular call", e);
    }

    return jsonResponse;
  }

  @GET
  @Path(value = "/data/contributor")
  @Produces(MediaType.APPLICATION_JSON)
  public String getContributorData(@QueryParam("dataset") String collection,
      @QueryParam("filters") String filterJson,
      @QueryParam("timeZone") @DefaultValue(DEFAULT_TIMEZONE_ID) String timeZone,
      @QueryParam("baselineStart") Long baselineStart, @QueryParam("baselineEnd") Long baselineEnd,
      @QueryParam("currentStart") Long currentStart, @QueryParam("currentEnd") Long currentEnd,
      @QueryParam("compareMode") String compareMode,
      @QueryParam("aggTimeGranularity") String aggTimeGranularity,
      @QueryParam("metrics") String metricsJson, @QueryParam("dimensions") String groupByDimensions)
      throws Exception {

    ContributorViewRequest request = new ContributorViewRequest();
    request.setCollection(collection);

    List<MetricExpression> metricExpressions =
        Utils.convertToMetricExpressions(metricsJson, collection);
    request.setMetricExpressions(metricExpressions);
    long maxDataTime = CACHE_REGISTRY_INSTANCE.getCollectionMaxDataTimeCache().get(collection);
    if (currentEnd > maxDataTime) {
      long delta = currentEnd - maxDataTime;
      currentEnd = currentEnd - delta;
      baselineEnd = baselineEnd - delta;
    }
    request.setBaselineStart(new DateTime(baselineStart, DateTimeZone.forID(timeZone)));
    request.setBaselineEnd(new DateTime(baselineEnd, DateTimeZone.forID(timeZone)));
    request.setCurrentStart(new DateTime(currentStart, DateTimeZone.forID(timeZone)));
    request.setCurrentEnd(new DateTime(currentEnd, DateTimeZone.forID(timeZone)));

    if (filterJson != null && !filterJson.isEmpty()) {
      filterJson = URLDecoder.decode(filterJson, "UTF-8");
      request.setFilters(ThirdEyeUtils.convertToMultiMap(filterJson));
    }
    request.setTimeGranularity(Utils.getAggregationTimeGranularity(aggTimeGranularity));
    if (groupByDimensions != null && !groupByDimensions.isEmpty()) {
      request.setGroupByDimensions(Arrays.asList(groupByDimensions.trim().split(",")));
    }
    ContributorViewHandler handler = new ContributorViewHandler(queryCache);
    String jsonResponse = null;

    try {
      ContributorViewResponse response = handler.process(request);
      jsonResponse =
          OBJECT_MAPPER.enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(response);
      LOG.debug("Contributor response {}", jsonResponse);

    } catch (Exception e) {
      LOG.error("Exception while processing /data/tabular call", e);
    }

    return jsonResponse;
  }

  @GET
  @Path(value = "/data/timeseries")
  @Produces(MediaType.APPLICATION_JSON)
  public String getTimeSeriesData(@QueryParam("dataset") String collection,
      @QueryParam("filters") String filterJson,
      @QueryParam("timeZone") @DefaultValue(DEFAULT_TIMEZONE_ID) String timeZone,
      @QueryParam("currentStart") Long start, @QueryParam("currentEnd") Long end,
      @QueryParam("aggTimeGranularity") String aggTimeGranularity,
      @QueryParam("metrics") String metricsJson, @QueryParam("dimensions") String groupByDimensions)
      throws Exception {
    TimeSeriesRequest request = new TimeSeriesRequest();
    request.setCollectionName(collection);
    request.setStart(new DateTime(start, DateTimeZone.forID(timeZone)));
    request.setEnd(new DateTime(end, DateTimeZone.forID(timeZone)));
    if (groupByDimensions != null && !groupByDimensions.isEmpty()) {
      request.setGroupByDimensions(Arrays.asList(groupByDimensions.trim().split(",")));
    }
    if (filterJson != null && !filterJson.isEmpty()) {
      filterJson = URLDecoder.decode(filterJson, "UTF-8");
      request.setFilterSet(ThirdEyeUtils.convertToMultiMap(filterJson));
    }
    List<MetricExpression> metricExpressions =
        Utils.convertToMetricExpressions(metricsJson, collection);
    request.setMetricExpressions(metricExpressions);
    request.setAggregationTimeGranularity(Utils.getAggregationTimeGranularity(aggTimeGranularity));

    TimeSeriesHandler handler = new TimeSeriesHandler(queryCache);
    String jsonResponse = "";
    try {
      TimeSeriesResponse response = handler.handle(request);
      JSONObject timeseriesMap = new JSONObject();
      JSONArray timeValueArray = new JSONArray();
      TreeSet<String> keys = new TreeSet<>();
      TreeSet<Long> times = new TreeSet<>();
      for (int i = 0; i < response.getNumRows(); i++) {
        TimeSeriesRow timeSeriesRow = response.getRow(i);
        times.add(timeSeriesRow.getStart());
      }
      for (Long time : times) {
        timeValueArray.put(time);
      }
      timeseriesMap.put("time", timeValueArray);
      for (int i = 0; i < response.getNumRows(); i++) {
        TimeSeriesRow timeSeriesRow = response.getRow(i);
        for (TimeSeriesMetric metricTimeSeries : timeSeriesRow.getMetrics()) {
          String key = metricTimeSeries.getMetricName();
          if (timeSeriesRow.getDimensionName() != null
              && timeSeriesRow.getDimensionName().trim().length() > 0) {
            key =
                key + "|" + timeSeriesRow.getDimensionName() + "|"
                    + timeSeriesRow.getDimensionValue();
          }
          JSONArray valueArray;
          if (!timeseriesMap.has(key)) {
            valueArray = new JSONArray();
            timeseriesMap.put(key, valueArray);
            keys.add(key);
          } else {
            valueArray = timeseriesMap.getJSONArray(key);
          }
          valueArray.put(metricTimeSeries.getValue());
        }
      }
      JSONObject jsonResponseObject = new JSONObject();
      jsonResponseObject.put("timeSeriesData", timeseriesMap);
      jsonResponseObject.put("keys", new JSONArray(keys));
      jsonResponse = jsonResponseObject.toString();
    } catch (Exception e) {
      throw e;
    }
    LOG.info("Response:{}", jsonResponse);
    return jsonResponse;

  }

  @GET
  @Path(value = "/thirdeye")
  @Produces(MediaType.APPLICATION_JSON)
  public String saySomethingAwesome(@QueryParam("praise") String praise) {
    JSONObject hello = new JSONObject();
    try {
      hello.put("thirdeye", praise);
    } catch (JSONException e) {
      // TODO Auto-generated catch block
    }
    return hello.toString();
  }

  @GET
  @Path(value = "/data")
  @Produces(MediaType.APPLICATION_JSON)
  public String getData(@QueryParam("type") String type) throws Exception {
    ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
    String response = null;

    String file = null;
    if (type.equals("dataset")) {
      List<String> collections = queryCache.getClient().getCollections();
      JSONArray array = new JSONArray(collections);
      response = array.toString();
      // file = "assets/data/getdataset.json";
    } else if (type.equals("metrics")) {
      file = "assets/data/getmetrics.json";
    } else if (type.equals("treemaps")) {
      file = "assets/data/gettreemaps.json";
    } else {
      throw new Exception("Invalid param!!");
    }
    if (response == null) {
      InputStream inputStream = classLoader.getResourceAsStream(file);

      // ClassLoader classLoader = getClass().getClassLoader();
      // InputStream inputStream = classLoader.getResourceAsStream("assets.data/getmetrics.json");

      try {
        response = IOUtils.toString(inputStream);
      } catch (IOException e) {
        response = e.toString();
      }
    }
    return response;
  }

}
