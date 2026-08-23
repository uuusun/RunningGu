package com.runninggu.server.itinerary.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.runninggu.server.itinerary.application.ItineraryBlockCommands.FieldUpdate;
import com.runninggu.server.itinerary.application.ItineraryBlockCommands.Patch;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

/** 누락 필드는 유지하고 명시적 null은 nullable 장소 값을 지운다. */
public class PatchItineraryBlockRequest {

    @Schema(pattern = "HH:mm")
    private String startTime;
    private String title;
    private String category;
    private String placeName;
    private String address;
    private BigDecimal lat;
    private BigDecimal lng;
    private String description;

    @JsonIgnore private boolean startTimePresent;
    @JsonIgnore private boolean titlePresent;
    @JsonIgnore private boolean categoryPresent;
    @JsonIgnore private boolean placeNamePresent;
    @JsonIgnore private boolean addressPresent;
    @JsonIgnore private boolean latPresent;
    @JsonIgnore private boolean lngPresent;
    @JsonIgnore private boolean descriptionPresent;

    @JsonSetter("startTime")
    public void setStartTime(String value) {
        startTimePresent = true;
        startTime = value;
    }

    @JsonSetter("title")
    public void setTitle(String value) {
        titlePresent = true;
        title = value;
    }

    @JsonSetter("category")
    public void setCategory(String value) {
        categoryPresent = true;
        category = value;
    }

    @JsonSetter("placeName")
    public void setPlaceName(String value) {
        placeNamePresent = true;
        placeName = value;
    }

    @JsonSetter("address")
    public void setAddress(String value) {
        addressPresent = true;
        address = value;
    }

    @JsonSetter("lat")
    public void setLat(BigDecimal value) {
        latPresent = true;
        lat = value;
    }

    @JsonSetter("lng")
    public void setLng(BigDecimal value) {
        lngPresent = true;
        lng = value;
    }

    @JsonSetter("description")
    public void setDescription(String value) {
        descriptionPresent = true;
        description = value;
    }

    public String getStartTime() {
        return startTime;
    }

    public String getTitle() {
        return title;
    }

    public String getCategory() {
        return category;
    }

    public String getPlaceName() {
        return placeName;
    }

    public String getAddress() {
        return address;
    }

    public BigDecimal getLat() {
        return lat;
    }

    public BigDecimal getLng() {
        return lng;
    }

    public String getDescription() {
        return description;
    }

    public Patch toCommand() {
        return new Patch(
                update(startTimePresent, startTime),
                update(titlePresent, title),
                update(categoryPresent, category),
                update(placeNamePresent, placeName),
                update(addressPresent, address),
                update(latPresent, lat),
                update(lngPresent, lng),
                update(descriptionPresent, description));
    }

    private <T> FieldUpdate<T> update(boolean present, T value) {
        return present ? FieldUpdate.present(value) : FieldUpdate.absent();
    }
}
