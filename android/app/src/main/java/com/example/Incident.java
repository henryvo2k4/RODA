package com.example;

public class Incident {
    private double lat;
    private double lng;
    private String type;
    private String description;
    private String imageUrl;
    private int distance;
    private String status;
    private String createdAt;
    private String approvedAt;

    public Incident(double lat, double lng, String type, String description, String imageUrl, int distance, String status, String createdAt, String approvedAt) {
        this.lat = lat;
        this.lng = lng;
        this.type = type;
        this.description = description;
        this.imageUrl = imageUrl;
        this.distance = distance;
        this.status = status;
        this.createdAt = createdAt;
        this.approvedAt = approvedAt;
    }

    public double getLat() { return lat; }
    public double getLng() { return lng; }
    public String getType() { return type; }
    public String getDescription() { return description; }
    public String getImageUrl() { return imageUrl; }
    public int getDistance() { return distance; }
    public String getStatus() { return status; }
    public String getCreatedAt() { return createdAt; }
    public String getApprovedAt() { return approvedAt; }
}
