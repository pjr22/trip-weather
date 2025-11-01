package com.pjr22.tripweather.model;

public class WeatherData {
    private String condition;
    private Integer temperature;
    private String temperatureUnit;
    private String windSpeed;
    private String windDirection;
    private String iconUrl;
    private Integer precipitationProbability;
    private String error;

    public WeatherData() {
    }

    public WeatherData(String condition, Integer temperature, String temperatureUnit, 
                       String windSpeed, String windDirection, String iconUrl, 
                       Integer precipitationProbability) {
        this.condition = condition;
        this.temperature = temperature;
        this.temperatureUnit = temperatureUnit;
        this.windSpeed = windSpeed;
        this.windDirection = windDirection;
        this.iconUrl = iconUrl;
        this.precipitationProbability = precipitationProbability;
    }

    public static WeatherData createError(String errorMessage) {
        WeatherData data = new WeatherData();
        data.error = errorMessage;
        return data;
    }

    public String getCondition() {
        return condition;
    }

    public void setCondition(String condition) {
        this.condition = condition;
    }

    public Integer getTemperature() {
        return temperature;
    }

    public void setTemperature(Integer temperature) {
        this.temperature = temperature;
    }

    public String getTemperatureUnit() {
        return temperatureUnit;
    }

    public void setTemperatureUnit(String temperatureUnit) {
        this.temperatureUnit = temperatureUnit;
    }

    public String getWindSpeed() {
        return windSpeed;
    }

    public void setWindSpeed(String windSpeed) {
        this.windSpeed = windSpeed;
    }

    public String getWindDirection() {
        return windDirection;
    }

    public void setWindDirection(String windDirection) {
        this.windDirection = windDirection;
    }

    public String getIconUrl() {
        return iconUrl;
    }

    public void setIconUrl(String iconUrl) {
        this.iconUrl = iconUrl;
    }

    public Integer getPrecipitationProbability() {
        return precipitationProbability;
    }

    public void setPrecipitationProbability(Integer precipitationProbability) {
        this.precipitationProbability = precipitationProbability;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public boolean hasError() {
        return error != null && !error.isEmpty();
    }
}
