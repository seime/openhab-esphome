package no.seime.openhab.binding.esphome.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/**
 * Ensures that the thing configuration keys for the {@code device} thing type are consistent across
 * {@link BindingConstants}, {@link ESPHomeConfiguration} (what the handler binds the configuration to), the thing
 * type XML and the README documentation.
 */
public class ConfigurationConstantsTest {

    private static final Path README = Path.of("README.md");
    private static final Path THING_XML = Path.of("src/main/resources/OH-INF/thing/thing-esphome.xml");

    private static Set<String> configurationClassFields() {
        return Arrays.stream(ESPHomeConfiguration.class.getDeclaredFields())
                .filter(f -> !Modifier.isStatic(f.getModifiers())).map(Field::getName)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    public void configConstantsMatchConfigurationClassFields() throws IllegalAccessException {
        Set<String> configFields = configurationClassFields();

        List<Field> constants = Arrays.stream(BindingConstants.class.getDeclaredFields())
                .filter(f -> Modifier.isStatic(f.getModifiers()) && Modifier.isFinal(f.getModifiers()))
                .filter(f -> f.getType() == String.class && f.getName().startsWith("CONFIG_")).toList();

        assertFalse(constants.isEmpty(), "No CONFIG_* constants found in BindingConstants");

        for (Field constant : constants) {
            String key = (String) constant.get(null);
            assertTrue(configFields.contains(key), "BindingConstants." + constant.getName() + " = \"" + key
                    + "\" does not match any field in ESPHomeConfiguration " + configFields);
        }
    }

    @Test
    public void readmeDocumentsAllConfigurationClassFields() throws IOException {
        String readme = Files.readString(README);
        int start = readme.indexOf("### `device` Thing Configuration");
        assertTrue(start >= 0, "README section '### `device` Thing Configuration' not found");
        int end = readme.indexOf("\n#", start + 1);
        String section = readme.substring(start, end < 0 ? readme.length() : end);

        Set<String> documented = new TreeSet<>();
        Matcher m = Pattern.compile("(?m)^\\|\\s*`([^`]+)`\\s*\\|").matcher(section);
        while (m.find()) {
            documented.add(m.group(1));
        }

        assertEquals(configurationClassFields(), documented,
                "README device configuration table does not match ESPHomeConfiguration fields");
    }

    @Test
    public void thingTypeXmlDeclaresAllConfigurationClassFields() throws IOException {
        String xml = Files.readString(THING_XML);
        int start = xml.indexOf("<thing-type id=\"device\"");
        assertTrue(start >= 0, "thing-type 'device' not found in " + THING_XML);
        String thingType = xml.substring(start, xml.indexOf("</thing-type>", start));

        Set<String> declared = new TreeSet<>();
        Matcher m = Pattern.compile("<parameter name=\"([^\"]+)\"").matcher(thingType);
        while (m.find()) {
            declared.add(m.group(1));
        }

        assertEquals(configurationClassFields(), declared,
                "thing-esphome.xml device parameters do not match ESPHomeConfiguration fields");
    }

    @Test
    public void thingTypeXmlDefaultsMatchConfigurationClassDefaults() throws Exception {
        String xml = Files.readString(THING_XML);
        int start = xml.indexOf("<thing-type id=\"device\"");
        String thingType = xml.substring(start, xml.indexOf("</thing-type>", start));

        ESPHomeConfiguration defaults = new ESPHomeConfiguration();
        Matcher m = Pattern.compile("(?s)<parameter name=\"([^\"]+)\".*?</parameter>").matcher(thingType);
        int checked = 0;
        while (m.find()) {
            Matcher d = Pattern.compile("<default>([^<]*)</default>").matcher(m.group());
            if (d.find()) {
                Object classDefault = ESPHomeConfiguration.class.getField(m.group(1)).get(defaults);
                assertEquals(d.group(1).trim(), String.valueOf(classDefault),
                        "Default for '" + m.group(1) + "' differs between thing-esphome.xml and ESPHomeConfiguration");
                checked++;
            }
        }
        assertTrue(checked > 0, "No <default> values found in thing-esphome.xml");
    }
}
