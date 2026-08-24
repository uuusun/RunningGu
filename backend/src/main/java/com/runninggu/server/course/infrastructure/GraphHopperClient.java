package com.runninggu.server.course.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.runninggu.server.course.application.OsmRoundTripSource;
import com.runninggu.server.course.application.OsmRouteCandidate;
import com.runninggu.server.course.application.OsmRouteCoordinate;
import com.runninggu.server.course.application.OsmRouteSourceException;
import com.runninggu.server.course.domain.GeoDistance;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** 서버 내부 GraphHopper의 run/round_trip 응답을 품질 지표로 변환한다. (SPEC §5.8) */
public class GraphHopperClient implements OsmRoundTripSource {

    private static final Set<String> MAJOR_ROAD_CLASSES = Set.of(
            "PRIMARY", "SECONDARY", "TRUNK", "TERTIARY", "MOTORWAY");
    private static final Set<Integer> TURN_SIGNS = Set.of(-98, -8, -3, -2, 2, 3, 6, 8);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final boolean enabled;

    public GraphHopperClient(
            RestClient restClient,
            ObjectMapper objectMapper,
            boolean enabled) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
    }

    @Override
    public Optional<OsmRouteCandidate> fetch(
            BigDecimal lat,
            BigDecimal lng,
            int requestedDistanceM,
            int seed) {
        if (!enabled) {
            throw new OsmRouteSourceException("GraphHopper가 비활성화되어 있습니다.");
        }
        try {
            String body = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/route")
                            .queryParam("point", lat.toPlainString() + "," + lng.toPlainString())
                            .queryParam("profile", "run")
                            .queryParam("algorithm", "round_trip")
                            .queryParam("round_trip.distance", requestedDistanceM)
                            .queryParam("round_trip.seed", seed)
                            .queryParam("points_encoded", false)
                            .queryParam("elevation", true)
                            .queryParam("instructions", true)
                            .queryParam("details", "road_class")
                            .build())
                    .retrieve()
                    .body(String.class);
            return parse(body, seed);
        } catch (RestClientException exception) {
            throw new OsmRouteSourceException("GraphHopper 호출에 실패했습니다.", exception);
        }
    }

    private Optional<OsmRouteCandidate> parse(String body, int seed) {
        try {
            if (body == null || body.isBlank()) {
                throw invalidResponse();
            }
            JsonNode paths = objectMapper.readTree(body).path("paths");
            if (!paths.isArray()) {
                throw invalidResponse();
            }
            if (paths.isEmpty()) {
                return Optional.empty();
            }
            JsonNode path = paths.get(0);
            double distanceM = finiteNonNegative(path.path("distance"));
            List<OsmRouteCoordinate> coordinates = coordinates(path);
            List<Double> segmentDistances = segmentDistances(coordinates);
            double polylineDistanceM = segmentDistances.stream()
                    .mapToDouble(Double::doubleValue)
                    .sum();
            if (distanceM <= 0 || polylineDistanceM <= 0) {
                throw invalidResponse();
            }
            double majorRoadDistanceM = majorRoadDistance(path, segmentDistances);
            int turnCount = turnCount(path);
            double gainM = gain(coordinates);
            return Optional.of(new OsmRouteCandidate(
                    seed,
                    distanceM,
                    gainM,
                    polylineDistanceM,
                    majorRoadDistanceM,
                    turnCount,
                    coordinates));
        } catch (JsonProcessingException | ArithmeticException exception) {
            throw new OsmRouteSourceException("GraphHopper 응답이 계약과 다릅니다.", exception);
        }
    }

    private List<OsmRouteCoordinate> coordinates(JsonNode path) {
        JsonNode rawCoordinates = path.path("points").path("coordinates");
        if (!rawCoordinates.isArray() || rawCoordinates.size() < 2) {
            throw invalidResponse();
        }
        List<OsmRouteCoordinate> coordinates = new ArrayList<>(rawCoordinates.size());
        for (JsonNode raw : rawCoordinates) {
            if (!raw.isArray() || raw.size() < 3) {
                throw invalidResponse();
            }
            BigDecimal lng = decimal(raw.get(0));
            BigDecimal lat = decimal(raw.get(1));
            BigDecimal elevation = decimal(raw.get(2));
            if (lat.compareTo(BigDecimal.valueOf(-90)) < 0
                    || lat.compareTo(BigDecimal.valueOf(90)) > 0
                    || lng.compareTo(BigDecimal.valueOf(-180)) < 0
                    || lng.compareTo(BigDecimal.valueOf(180)) > 0) {
                throw invalidResponse();
            }
            coordinates.add(new OsmRouteCoordinate(lat, lng, elevation));
        }
        return List.copyOf(coordinates);
    }

    private List<Double> segmentDistances(List<OsmRouteCoordinate> coordinates) {
        List<Double> distances = new ArrayList<>(coordinates.size() - 1);
        for (int index = 1; index < coordinates.size(); index++) {
            OsmRouteCoordinate previous = coordinates.get(index - 1);
            OsmRouteCoordinate current = coordinates.get(index);
            distances.add(GeoDistance.meters(
                    previous.lat(),
                    previous.lng(),
                    current.lat(),
                    current.lng()));
        }
        return List.copyOf(distances);
    }

    private double majorRoadDistance(JsonNode path, List<Double> segmentDistances) {
        JsonNode details = path.path("details").path("road_class");
        if (!details.isArray() || details.isEmpty()) {
            throw invalidResponse();
        }
        double majorRoadDistanceM = 0;
        for (JsonNode detail : details) {
            if (!detail.isArray()
                    || detail.size() < 3
                    || !detail.get(0).canConvertToInt()
                    || !detail.get(1).canConvertToInt()
                    || !detail.get(2).isTextual()) {
                throw invalidResponse();
            }
            int fromRef = detail.get(0).intValue();
            int toRef = detail.get(1).intValue();
            if (fromRef < 0
                    || fromRef >= toRef
                    || toRef > segmentDistances.size()) {
                throw invalidResponse();
            }
            String roadClass = detail.get(2).textValue().toUpperCase(Locale.ROOT);
            if (MAJOR_ROAD_CLASSES.contains(roadClass)) {
                for (int index = fromRef; index < toRef; index++) {
                    majorRoadDistanceM += segmentDistances.get(index);
                }
            }
        }
        return majorRoadDistanceM;
    }

    private int turnCount(JsonNode path) {
        JsonNode instructions = path.path("instructions");
        if (!instructions.isArray()) {
            throw invalidResponse();
        }
        int turns = 0;
        for (JsonNode instruction : instructions) {
            JsonNode sign = instruction.path("sign");
            if (sign.canConvertToInt() && TURN_SIGNS.contains(sign.intValue())) {
                turns++;
            }
        }
        return turns;
    }

    private double gain(List<OsmRouteCoordinate> coordinates) {
        double gain = 0;
        for (int index = 1; index < coordinates.size(); index++) {
            double delta = coordinates.get(index).elevationM().doubleValue()
                    - coordinates.get(index - 1).elevationM().doubleValue();
            if (!Double.isFinite(delta)) {
                throw invalidResponse();
            }
            gain += Math.max(0, delta);
        }
        return gain;
    }

    private BigDecimal decimal(JsonNode node) {
        if (node == null || !node.isNumber() || !Double.isFinite(node.doubleValue())) {
            throw invalidResponse();
        }
        return node.decimalValue();
    }

    private double finiteNonNegative(JsonNode node) {
        if (!node.isNumber()) {
            throw invalidResponse();
        }
        double value = node.doubleValue();
        if (!Double.isFinite(value) || value < 0) {
            throw invalidResponse();
        }
        return value;
    }

    private OsmRouteSourceException invalidResponse() {
        return new OsmRouteSourceException("GraphHopper 응답이 계약과 다릅니다.");
    }
}
