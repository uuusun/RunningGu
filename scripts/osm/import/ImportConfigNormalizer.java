import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public final class ImportConfigNormalizer {
    private static final Set<String> EXACT_KEYS = Set.of(
            "datareader.file",
            "graph.encoded_values",
            "profiles",
            "profiles_ch",
            "profiles_lm"
    );
    private static final Set<String> REQUIRED_KEYS = EXACT_KEYS;
    private static final Set<String> REQUIRED_IMPORT_KEYS = Set.of(
            "import.osm.ignored_highways",
            "graph.elevation.provider",
            "graph.elevation.cache_dir"
    );

    private ImportConfigNormalizer() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("사용법: ImportConfigNormalizer <config.yml> <pbf_file_name>");
        }
        String pbfFileName = args[1];
        if (!Path.of(pbfFileName).getFileName().toString().equals(pbfFileName)) {
            throw new IllegalArgumentException("PBF 입력은 directory 없는 파일명이어야 합니다.");
        }

        ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
        JsonNode root = yaml.readTree(Path.of(args[0]).toFile());
        JsonNode graphhopper = root.path("graphhopper");
        if (!graphhopper.isObject()) {
            throw new IllegalArgumentException("GraphHopper YAML에 graphhopper object가 없습니다.");
        }

        Map<String, JsonNode> allowed = new TreeMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = graphhopper.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String key = field.getKey();
            if (EXACT_KEYS.contains(key)
                    || key.startsWith("import.osm.")
                    || key.startsWith("prepare.")
                    || key.startsWith("graph.elevation.")) {
                allowed.put(key, field.getValue());
            }
        }
        if (!allowed.keySet().containsAll(REQUIRED_KEYS)
                || !allowed.keySet().containsAll(REQUIRED_IMPORT_KEYS)) {
            throw new IllegalArgumentException("import 설정 allowlist 필드가 빠졌습니다.");
        }

        String configuredPbf = allowed.get("datareader.file").asText();
        if (!Path.of(configuredPbf).getFileName().toString().equals(pbfFileName)) {
            throw new IllegalArgumentException("datareader.file basename과 manifest PBF 파일명이 다릅니다.");
        }
        ObjectMapper json = new ObjectMapper();
        allowed.put("datareader.file", json.getNodeFactory().textNode(pbfFileName));
        if (allowed.containsKey("graph.elevation.cache_dir")) {
            allowed.put("graph.elevation.cache_dir", json.getNodeFactory().textNode("$SRTM_CACHE"));
        }
        rejectHostPaths(json.valueToTree(allowed), "importConfig");

        json.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        json.configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true);
        System.out.print(json.writeValueAsString(allowed));
    }

    private static void rejectHostPaths(JsonNode node, String field) throws JsonProcessingException {
        if (node.isTextual()) {
            String value = node.asText();
            if (value.startsWith("/") || value.matches("^[A-Za-z]:[\\\\/].*")) {
                throw new IllegalArgumentException(field + "에 정규화되지 않은 host 절대경로가 있습니다: " + value);
            }
            return;
        }
        if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) {
                rejectHostPaths(node.get(index), field + "[" + index + "]");
            }
            return;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> child = fields.next();
                rejectHostPaths(child.getValue(), field + "." + child.getKey());
            }
        }
    }
}
