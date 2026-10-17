package ca.sfu.bustracker.collector;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "collector")
public class CollectorProperties {
    private String apiKey = "";
    private String tripUpdatesUrl = "https://gtfsapi.translink.ca/v3/gtfsrealtime";
    private String positionsUrl = "https://gtfsapi.translink.ca/v3/gtfsposition";
    private String staticGtfsUrl = "https://gtfs-static.translink.ca/gtfs/google_transit.zip";
    private int dailyRequestBudget = 960;
    private long tripUpdateIntervalMs = 120_000;
    private long positionIntervalMs = 360_000;
    private Duration httpTimeout = Duration.ofSeconds(20);
    private String healthcheckUrl = "";
    private String archiveDir = "./data/archive";
    private Duration archiveEvery = Duration.ofMinutes(10);
    private String zone = "America/Vancouver";

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getTripUpdatesUrl() { return tripUpdatesUrl; }
    public void setTripUpdatesUrl(String tripUpdatesUrl) { this.tripUpdatesUrl = tripUpdatesUrl; }
    public String getPositionsUrl() { return positionsUrl; }
    public void setPositionsUrl(String positionsUrl) { this.positionsUrl = positionsUrl; }
    public String getStaticGtfsUrl() { return staticGtfsUrl; }
    public void setStaticGtfsUrl(String staticGtfsUrl) { this.staticGtfsUrl = staticGtfsUrl; }
    public int getDailyRequestBudget() { return dailyRequestBudget; }
    public void setDailyRequestBudget(int dailyRequestBudget) { this.dailyRequestBudget = dailyRequestBudget; }
    public long getTripUpdateIntervalMs() { return tripUpdateIntervalMs; }
    public void setTripUpdateIntervalMs(long tripUpdateIntervalMs) { this.tripUpdateIntervalMs = tripUpdateIntervalMs; }
    public long getPositionIntervalMs() { return positionIntervalMs; }
    public void setPositionIntervalMs(long positionIntervalMs) { this.positionIntervalMs = positionIntervalMs; }
    public Duration getHttpTimeout() { return httpTimeout; }
    public void setHttpTimeout(Duration httpTimeout) { this.httpTimeout = httpTimeout; }
    public String getHealthcheckUrl() { return healthcheckUrl; }
    public void setHealthcheckUrl(String healthcheckUrl) { this.healthcheckUrl = healthcheckUrl; }
    public String getArchiveDir() { return archiveDir; }
    public void setArchiveDir(String archiveDir) { this.archiveDir = archiveDir; }
    public Duration getArchiveEvery() { return archiveEvery; }
    public void setArchiveEvery(Duration archiveEvery) { this.archiveEvery = archiveEvery; }
    public String getZone() { return zone; }
    public void setZone(String zone) { this.zone = zone; }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
