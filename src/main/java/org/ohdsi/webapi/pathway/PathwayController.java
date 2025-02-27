package org.ohdsi.webapi.pathway;

import com.odysseusinc.arachne.commons.utils.ConverterUtils;
import org.ohdsi.webapi.Constants;
import org.ohdsi.webapi.Pagination;
import org.ohdsi.webapi.check.CheckResult;
import org.ohdsi.webapi.check.checker.pathway.PathwayChecker;
import org.ohdsi.webapi.common.SourceMapKey;
import org.ohdsi.webapi.common.generation.CommonGenerationDTO;
import org.ohdsi.webapi.common.sensitiveinfo.CommonGenerationSensitiveInfoService;
import org.ohdsi.webapi.i18n.I18nService;
import org.ohdsi.webapi.job.JobExecutionResource;
import org.ohdsi.webapi.pathway.converter.SerializedPathwayAnalysisToPathwayAnalysisConverter;
import org.ohdsi.webapi.pathway.domain.PathwayAnalysisEntity;
import org.ohdsi.webapi.pathway.domain.PathwayAnalysisGenerationEntity;
import org.ohdsi.webapi.pathway.dto.*;
import org.ohdsi.webapi.pathway.dto.internal.PathwayAnalysisResult;
import org.ohdsi.webapi.security.PermissionService;
import org.ohdsi.webapi.source.SourceService;
import org.ohdsi.webapi.source.Source;
import org.ohdsi.webapi.tag.TagService;
import org.ohdsi.webapi.tag.dto.TagNameListRequestDTO;
import org.ohdsi.webapi.util.ExportUtil;
import org.ohdsi.webapi.util.ExceptionUtils;
import org.ohdsi.webapi.versioning.dto.VersionDTO;
import org.ohdsi.webapi.versioning.dto.VersionUpdateDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.convert.ConversionService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;

import javax.transaction.Transactional;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Path("/pathway-analysis")
@Controller
public class PathwayController {

    private ConversionService conversionService;
    private ConverterUtils converterUtils;
    private PathwayService pathwayService;
    private final SourceService sourceService;
    private final CommonGenerationSensitiveInfoService<CommonGenerationDTO> sensitiveInfoService;
    private final I18nService i18nService;
    private PathwayChecker checker;
    private PermissionService permissionService;
    private final TagService tagService;

    @Autowired
    public PathwayController(ConversionService conversionService, ConverterUtils converterUtils, PathwayService pathwayService, SourceService sourceService, CommonGenerationSensitiveInfoService sensitiveInfoService, PathwayChecker checker, PermissionService permissionService, I18nService i18nService, TagService tagService) {

        this.conversionService = conversionService;
        this.converterUtils = converterUtils;
        this.pathwayService = pathwayService;
        this.sourceService = sourceService;
        this.sensitiveInfoService = sensitiveInfoService;
        this.i18nService = i18nService;
        this.checker = checker;
        this.permissionService = permissionService;
        this.tagService = tagService;
    }

    @POST
    @Path("/")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public PathwayAnalysisDTO create(final PathwayAnalysisDTO dto) {

        PathwayAnalysisEntity pathwayAnalysis = conversionService.convert(dto, PathwayAnalysisEntity.class);
        PathwayAnalysisEntity saved = pathwayService.create(pathwayAnalysis);
        return reloadAndConvert(saved.getId());
    }

    @POST
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @Transactional
    public PathwayAnalysisDTO copy(@PathParam("id") final Integer id) {

        PathwayAnalysisDTO dto = get(id);
        dto.setId(null);
        dto.setName(pathwayService.getNameForCopy(dto.getName()));
        dto.setTags(null);
        return create(dto);
    }

    @POST
    @Path("/import")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public PathwayAnalysisDTO importAnalysis(final PathwayAnalysisExportDTO dto) {
        dto.setTags(null);
        dto.setName(pathwayService.getNameWithSuffix(dto.getName()));
        PathwayAnalysisEntity pathwayAnalysis = conversionService.convert(dto, PathwayAnalysisEntity.class);
        PathwayAnalysisEntity imported = pathwayService.importAnalysis(pathwayAnalysis);
        return reloadAndConvert(imported.getId());
    }

    @GET
    @Path("/")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Page<PathwayAnalysisDTO> list(@Pagination Pageable pageable) {

        return pathwayService.getPage(pageable).map(pa -> {
            PathwayAnalysisDTO dto = conversionService.convert(pa, PathwayAnalysisDTO.class);
            permissionService.fillWriteAccess(pa, dto);
            return dto;
        });
    }

    @GET
    @Path("/{id}/exists")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public int getCountPAWithSameName(@PathParam("id") @DefaultValue("0") final int id, @QueryParam("name") String name) {
        
        return pathwayService.getCountPAWithSameName(id, name);
    }

    @PUT
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public PathwayAnalysisDTO update(@PathParam("id") final Integer id, @RequestBody final PathwayAnalysisDTO dto) {
        pathwayService.saveVersion(id);
        PathwayAnalysisEntity pathwayAnalysis = conversionService.convert(dto, PathwayAnalysisEntity.class);
        pathwayAnalysis.setId(id);
        pathwayService.update(pathwayAnalysis);
        return reloadAndConvert(id);
    }

    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public PathwayAnalysisDTO get(@PathParam("id") final Integer id) {
        PathwayAnalysisEntity pathwayAnalysis = pathwayService.getById(id);
        ExceptionUtils.throwNotFoundExceptionIfNull(pathwayAnalysis, String.format(i18nService.translate("pathways.manager.messages.notfound", "There is no pathway analysis with id = %d."), id));
        Map<Integer, Integer> eventCodes = pathwayService.getEventCohortCodes(pathwayAnalysis);

        PathwayAnalysisDTO dto = conversionService.convert(pathwayAnalysis, PathwayAnalysisDTO.class);
        dto.getEventCohorts().forEach(ec -> ec.setCode(eventCodes.get(ec.getId())));

        return dto;
    }

    @GET
    @Path("/{id}/export")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public String export(@PathParam("id") final Integer id) {

        PathwayAnalysisEntity pathwayAnalysis = pathwayService.getById(id);
        ExportUtil.clearCreateAndUpdateInfo(pathwayAnalysis);
        return new SerializedPathwayAnalysisToPathwayAnalysisConverter().convertToDatabaseColumn(pathwayAnalysis);
    }

    @GET
    @Path("/{id}/sql/{sourceKey}")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    @Consumes(MediaType.APPLICATION_JSON)
    public String getAnalysisSql(@PathParam("id") final Integer id, @PathParam("sourceKey") final String sourceKey) {

        Source source = sourceService.findBySourceKey(sourceKey);
        return pathwayService.buildAnalysisSql(-1L, pathwayService.getById(id), source.getSourceId());
    }

    @DELETE
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public void delete(@PathParam("id") final Integer id) {

        pathwayService.delete(id);
    }

    @POST
    @Path("/{id}/generation/{sourceKey}")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public JobExecutionResource generatePathways(
            @PathParam("id") final Integer pathwayAnalysisId,
            @PathParam("sourceKey") final String sourceKey
    ) {

        PathwayAnalysisEntity pathwayAnalysis = pathwayService.getById(pathwayAnalysisId);
        ExceptionUtils.throwNotFoundExceptionIfNull(pathwayAnalysis, String.format("There is no pathway analysis with id = %d.", pathwayAnalysisId));
        PathwayAnalysisDTO pathwayAnalysisDTO = conversionService.convert(pathwayAnalysis, PathwayAnalysisDTO.class);
        CheckResult checkResult = runDiagnostics(pathwayAnalysisDTO);
        if (checkResult.hasCriticalErrors()) {
            throw new RuntimeException("Cannot be generated due to critical errors in design. Call 'check' service for further details");
        }
        Source source = sourceService.findBySourceKey(sourceKey);
        return pathwayService.generatePathways(pathwayAnalysisId, source.getSourceId());
    }

    @DELETE
    @Path("/{id}/generation/{sourceKey}")
    public void cancelPathwaysGeneration(
            @PathParam("id") final Integer pathwayAnalysisId,
            @PathParam("sourceKey") final String sourceKey
    ){

        Source source = sourceService.findBySourceKey(sourceKey);
        pathwayService.cancelGeneration(pathwayAnalysisId, source.getSourceId());
    }

    @GET
    @Path("/{id}/generation")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public List<CommonGenerationDTO> getPathwayGenerations(
            @PathParam("id") final Integer pathwayAnalysisId
    ) {

        Map<String, Source> sourcesMap = sourceService.getSourcesMap(SourceMapKey.BY_SOURCE_KEY);
        return sensitiveInfoService.filterSensitiveInfo(converterUtils.convertList(pathwayService.getPathwayGenerations(pathwayAnalysisId), CommonGenerationDTO.class),
                info -> Collections.singletonMap(Constants.Variables.SOURCE, sourcesMap.get(info.getSourceKey())));
    }

    @GET
    @Path("/generation/{generationId}")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public CommonGenerationDTO getPathwayGenerations(
            @PathParam("generationId") final Long generationId
    ) {

        PathwayAnalysisGenerationEntity generationEntity = pathwayService.getGeneration(generationId);
        return sensitiveInfoService.filterSensitiveInfo(conversionService.convert(generationEntity, CommonGenerationDTO.class),
                Collections.singletonMap(Constants.Variables.SOURCE, generationEntity.getSource()));
    }

    @GET
    @Path("/generation/{generationId}/design")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public String getGenerationDesign(
            @PathParam("generationId") final Long generationId
    ) {

        return pathwayService.findDesignByGenerationId(generationId);

    }

    @GET
    @Path("/generation/{generationId}/result")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public PathwayPopulationResultsDTO getGenerationResults(
            @PathParam("generationId") final Long generationId
    ) {

        PathwayAnalysisResult resultingPathways = pathwayService.getResultingPathways(generationId);

        List<PathwayCodeDTO> eventCodeDtos = resultingPathways.getCodes()
                .stream()
                .map(entry -> {
                    PathwayCodeDTO dto = new PathwayCodeDTO();
                    dto.setCode(entry.getCode());
                    dto.setName(entry.getName());
                    dto.setIsCombo(entry.isCombo());
                    return dto;
                })
                .collect(Collectors.toList());

        List<TargetCohortPathwaysDTO> pathwayDtos = resultingPathways.getCohortPathwaysList()
                .stream()
                .map(cohortResults -> {
                    if (cohortResults.getPathwaysCounts() == null) {
                        return null;
                    }

                    List<PathwayPopulationEventDTO> eventDTOs = cohortResults.getPathwaysCounts()
                            .entrySet()
                            .stream()
                            .map(entry -> new PathwayPopulationEventDTO(entry.getKey(), entry.getValue()))
                            .collect(Collectors.toList());
                    return new TargetCohortPathwaysDTO(cohortResults.getCohortId(), cohortResults.getTargetCohortCount(), cohortResults.getTotalPathwaysCount(), eventDTOs);
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        return new PathwayPopulationResultsDTO(eventCodeDtos, pathwayDtos);
    }

    private PathwayAnalysisDTO reloadAndConvert(Integer id) {
        // Before conversion entity must be refreshed to apply entity graphs
        PathwayAnalysisEntity analysis = pathwayService.getById(id);
        return conversionService.convert(analysis, PathwayAnalysisDTO.class);
    }

    @POST
    @Path("/check")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public CheckResult runDiagnostics(PathwayAnalysisDTO pathwayAnalysisDTO){

        return new CheckResult(checker.check(pathwayAnalysisDTO));
    }

    /**
     * Assign tag to Pathway Analysis
     *
     * @param id
     * @param tagId
     */
    @POST
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/{id}/tag/")
    public void assignTag(@PathParam("id") final int id, final int tagId) {
        pathwayService.assignTag(id, tagId, false);
    }

    /**
     * Unassign tag from Pathway Analysis
     *
     * @param id
     * @param tagId
     */
    @DELETE
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/{id}/tag/{tagId}")
    public void unassignTag(@PathParam("id") final int id, @PathParam("tagId") final int tagId) {
        pathwayService.unassignTag(id, tagId, false);
    }

    /**
     * Assign protected tag to Pathway Analysis
     *
     * @param id
     * @param tagId
     */
    @POST
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/{id}/protectedtag/")
    public void assignPermissionProtectedTag(@PathParam("id") final int id, final int tagId) {
        pathwayService.assignTag(id, tagId, true);
    }

    /**
     * Unassign protected tag from Pathway Analysis
     *
     * @param id
     * @param tagId
     */
    @DELETE
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/{id}/protectedtag/{tagId}")
    public void unassignPermissionProtectedTag(@PathParam("id") final int id, @PathParam("tagId") final int tagId) {
        pathwayService.unassignTag(id, tagId, true);
    }

    /**
     * Get list of versions of Pathway Analysis
     *
     * @param id
     * @return
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/{id}/version/")
    public List<VersionDTO> getVersions(@PathParam("id") final long id) {
        return pathwayService.getVersions(id);
    }

    /**
     * Get version of Pathway Analysis
     *
     * @param id
     * @param version
     * @return
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/{id}/version/{version}")
    public PathwayVersionFullDTO getVersion(@PathParam("id") final int id, @PathParam("version") final int version) {
        return pathwayService.getVersion(id, version);
    }

    /**
     * Update version of Pathway Analysis
     *
     * @param id
     * @param version
     * @param updateDTO
     * @return
     */
    @PUT
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/{id}/version/{version}")
    public VersionDTO updateVersion(@PathParam("id") final int id, @PathParam("version") final int version,
                             VersionUpdateDTO updateDTO) {
        return pathwayService.updateVersion(id, version, updateDTO);
    }

    /**
     * Delete version of Pathway Analysis
     *
     * @param id
     * @param version
     */
    @DELETE
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/{id}/version/{version}")
    public void deleteVersion(@PathParam("id") final int id, @PathParam("version") final int version){
        pathwayService.deleteVersion(id, version);
    }

    /**
     * Create a new asset form version of Pathway Analysis
     *
     * @param id
     * @param version
     * @return
     */
    @PUT
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/{id}/version/{version}/createAsset")
    public PathwayAnalysisDTO copyAssetFromVersion(@PathParam("id") final int id, @PathParam("version") final int version){
        return pathwayService.copyAssetFromVersion(id, version);
    }

    /**
     * Get list of pathways with assigned tags
     *
     * @param requestDTO
     * @return
     */
    @POST
    @Path("/byTags")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public List<PathwayAnalysisDTO> listByTags(TagNameListRequestDTO requestDTO) {
        if (requestDTO == null || requestDTO.getNames() == null || requestDTO.getNames().isEmpty()) {
            return Collections.emptyList();
        }
        return pathwayService.listByTags(requestDTO);
    }
}
