package com.example.utility;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.jcr.RepositoryException;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;

import com.adobe.acs.commons.fam.ActionManager;
import com.adobe.acs.commons.mcp.ProcessDefinition;
import com.adobe.acs.commons.mcp.ProcessInstance;
import com.adobe.acs.commons.mcp.form.Description;
import com.adobe.acs.commons.mcp.form.FormField;
import com.adobe.acs.commons.mcp.form.PathfieldComponent.NodeSelectComponent;
import com.adobe.acs.commons.mcp.form.RadioComponent;
import com.adobe.acs.commons.mcp.model.GenericReport;
import com.adobe.acs.commons.mcp.model.ManagedProcess;
import com.adobe.acs.commons.util.visitors.TreeFilteringResourceVisitor;

/**
 * Relocate Pages and/or Sites using a parallelized move process
 */
public class ReferenceUpdater extends ProcessDefinition {

    public enum PublishMethod {
        @Description("Select this option to generate Reference Report")
        REFERENCE_REPORT,
        @Description("Select this option to Execute the Reference Update")
        EXECUTE_UPDATE
    }

    @FormField(name = "Search String",
            description = "Provide the refernce search text",
            hint = "/content/example/us",
            component = NodeSelectComponent.class,
            required = false,
            options = {"base=/content"})
    private static String searchString;

    @FormField(name = "Search Path",
            description = "Provide the Search path to find the references",
            hint = "/content/globalbluprint/fr/en",
            component = NodeSelectComponent.class,
            required = false,
            options = {"base=/content"})
    private String destinationJcrPath;

    @FormField(name = "Exclude Properties",
            description = "Comma-separated list of properties to ignore",
            hint = "cq:template,cq:allowedTemplates,....",
            required = false,
            options = {"default=sling:vanityPath"})
    private String excludeProperties;

    @FormField(name = "Include Properties",
            description = "Comma-separated list of properties to ignore",
            hint = "cq:template,cq:allowedTemplates,....",
            required = false,
            options = {"default=sling:vanityPath"})
    private String includeProperties;

    @FormField(name = "Replace With",
            description = "Provide the Replace String to be replace",
            hint = "/content/globalbluprint/fr/example",
            component = NodeSelectComponent.class,
            required = false,
            options = {"base=/content"})
    private static String replaceString;

    @FormField(name = "Type",
            description = "Please select the oprporiate option to execute",
            component = RadioComponent.EnumerationSelector.class,
            options = {"vertical", "default=REFERENCE_REPORT"})
    public PublishMethod publishMethod = PublishMethod.REFERENCE_REPORT;

    private static Pattern pattern;
    private static Matcher matcher;
    private static final String HTML_TAG_PATTERN = "<(\"[^\"]*\"|'[^']*'|[^'\">])*>";
    private transient Set<String> excludeList;
    private transient Set<String> includeList;

    @Override
    public void init() throws RepositoryException {
        excludeList = Arrays.stream(excludeProperties.split(",")).map(String::trim).collect(Collectors.toSet());
        includeList = Arrays.stream(includeProperties.split(",")).map(String::trim).collect(Collectors.toSet());
        ReferenceUpdater.pattern = Pattern.compile(HTML_TAG_PATTERN);
    }

    ManagedProcess instanceInfo;

    @Override
    public void buildProcess(ProcessInstance instance, ResourceResolver rr) throws LoginException, RepositoryException {
        instanceInfo = instance.getInfo();
        String desc = "Executing : "+publishMethod.name().toLowerCase();
        switch (publishMethod.name().toLowerCase()) {
            case "reference_report":
                instance.defineAction("Collect Refs", rr, this::buildReport);
                break;
            case "execute_update":
                instance.defineAction("Update Refs", rr, this::executeReferenceUpdate);
                break;
        }
        instance.getInfo().setDescription(desc);
    }

    @Override
    public void storeReport(ProcessInstance instance, ResourceResolver rr) throws RepositoryException, PersistenceException {
        GenericReport genericReport = new GenericReport();
        genericReport.setRows(reportData, "Source", Report.class);
        genericReport.persist(rr, instance.getPath() + "/jcr:content/report");

    }

    @SuppressWarnings("squid:S00115")
    enum Report {
        references
    }

    private final transient Map<String, EnumMap<Report, Object>> reportData = new ConcurrentHashMap<>();

    public void buildReport(ActionManager manager) {
        TreeFilteringResourceVisitor visitor = new TreeFilteringResourceVisitor();
        visitor.setBreadthFirstMode();
        visitor.setTraversalFilterChecked(null);
        visitor.setResourceVisitor((resource, depth) -> {
            manager.deferredWithResolver(rr -> {
                Map<String, List<String>> references = collectReferences(resource, excludeList, includeList);
                for(Map.Entry<String, List<String>> ref : references.entrySet()){
                    String propertyPath = ref.getKey();
                    List<String> refs = ref.getValue();
                    reportData.put(propertyPath, new EnumMap<>(Report.class));
                    reportData.get(propertyPath).put(Report.references, refs.stream().collect(Collectors.joining(",")));
                }
            });
        });
        manager.deferredWithResolver(rr -> visitor.accept(rr.getResource(destinationJcrPath)));
    }

    public void executeReferenceUpdate(ActionManager manager) {
        TreeFilteringResourceVisitor visitor = new TreeFilteringResourceVisitor();
        visitor.setBreadthFirstMode();
        visitor.setTraversalFilterChecked(null);
        visitor.setResourceVisitor((resource, depth) -> {
            manager.deferredWithResolver(rr -> {
                Map<String, List<String>> references = updateReferences(resource, excludeList, includeList);
                for(Map.Entry<String, List<String>> ref : references.entrySet()){
                    String propertyPath = ref.getKey();
                    List<String> refs = ref.getValue();
                    reportData.put(propertyPath, new EnumMap<>(Report.class));
                    reportData.get(propertyPath).put(Report.references, refs.stream().collect(Collectors.joining(",")));
                }
            });
        });
        manager.deferredWithResolver(rr -> visitor.accept(rr.getResource(destinationJcrPath)));
    }

    /**
     * Collect references from a JCR property.
     * A property can be one of:
     * @param property an entry from a ValueMap
     * @return stream containing extracted references
     */
    static Stream<String> collectPaths(Map.Entry<String, Object> property) {
        Object p = property.getValue();

        Stream<String> stream;
        if (p.getClass() == String[].class) {
            stream = Arrays.stream((String[]) p);
        } else if (p.getClass() == String.class){
            stream = Stream.of((String) p);
        } else {
            stream = Stream.empty();
        }
        return stream;
    }

    /**
     * Collect broken references from properties of the given resource
     *
     * @param resource      the resource to check
     * @param includeList
     * @param excludeList2
     * @param searchString  searchString to to detect properties containing references. Set from @FormField
     * @return references keyed by property. The value is a List because a property can contain multiple links,
     * e.g. if it is multivalued or it is string.
     */
    static Map<String, List<String>> collectReferences(Resource resource, Set<String> skipList, Set<String> includeList) {

        return resource.getValueMap().entrySet().stream()
                .filter(entry -> includeList.contains(StringUtils.EMPTY) ? true : includeList.contains(entry.getKey()))
                .filter(entry -> !skipList.contains(entry.getKey()))
                .collect(Collectors.toMap(
                        entry -> resource.getPath() + "/" + entry.getKey(),
                        entry -> {
                            List<String> referncePaths =  collectPaths(entry)
                                    .filter(reference -> reference.contains(searchString))
                                    .filter(tag -> !validate(tag))
                                    .collect(Collectors.toList());
                            return referncePaths;
                        })).entrySet().stream().filter(e -> !e.getValue().isEmpty())
                .collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue()));
    }

    /**
     * Collect broken references from properties of the given resource
     *
     * @param resource      the resource to check
     * @param skipList
     * @param includeList
     * @return references keyed by property. The value is a List because a property can contain multiple links,
     * e.g. if it is multivalued or it is string.
     */
    static Map<String, List<String>> updateReferences(Resource resource, Set<String> skipList, Set<String> includeList) {

        return resource.getValueMap().entrySet().stream()
                .filter(entry -> includeList.contains(StringUtils.EMPTY) ? true : includeList.contains(entry.getKey()))
                .filter(entry -> !skipList.contains(entry.getKey()))
                .collect(Collectors.toMap(
                        entry -> resource.getPath() + "/" + entry.getKey(),
                        entry -> {
                            List<String> referncePaths =  collectPaths(entry, resource, entry.getKey())
                                    .filter(reference -> reference.contains(replaceString))
                                    .collect(Collectors.toList());
                            return referncePaths;
                        })).entrySet().stream().filter(e -> !e.getValue().isEmpty())
                .collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue()));
    }

    public static Stream<String> collectPaths(Entry<String, Object> property, Resource resource,
                                              String key) {
        Object p = property.getValue();
        String propertyType = StringUtils.EMPTY;

        List<String> propertyList = new ArrayList<>();
        if (p.getClass() == String[].class) {
            propertyList = Arrays.asList((String[]) p);
            propertyType = "multiple";
        } else if (p.getClass() == String.class){
            propertyList.add((String) p);
            propertyType = "single";
        }
        persistValue(resource, key, propertyList, propertyType);
        return propertyList.stream();
    }

    private static void persistValue(Resource resource, String key, List<String> propertyList, String propertyType) {
        if(propertyList.toString().contains(searchString)) {
            propertyList.replaceAll(e -> e.replace(searchString, replaceString) );
            try {
                ModifiableValueMap map = resource.adaptTo(ModifiableValueMap.class);
                if(propertyList.size() == 1 && propertyType.equalsIgnoreCase("single")) {
                    map.put(key, propertyList.get(0));
                } else {
                    map.put(key, propertyList.toArray());
                }
                resource.getResourceResolver().commit();
                resource.getResourceResolver().refresh();
            } catch (PersistenceException e) {
                e.printStackTrace();
            }
        }
    }

    // access from unit tests
    Map<String, EnumMap<Report, Object>> getReportData() {
        return reportData;
    }

    /**
     * Validate html tag with regular expression
     * @param tag html tag for validation
     * @return true valid html tag, false invalid html tag
     */
    public static boolean validate(String tag){
        matcher = pattern.matcher(tag);
        return matcher.lookingAt();
    }

}
