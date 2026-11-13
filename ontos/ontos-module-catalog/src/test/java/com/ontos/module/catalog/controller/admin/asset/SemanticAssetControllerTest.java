package com.ontos.module.catalog.controller.admin.asset;

import com.ontos.framework.common.exception.ServiceException;
import com.ontos.framework.common.exception.enums.GlobalErrorCodeConstants;
import com.ontos.framework.common.util.json.JsonUtils;
import com.ontos.module.catalog.controller.admin.asset.vo.SemanticAssetCreateReqVO;
import com.ontos.module.catalog.controller.admin.asset.vo.SemanticAssetOptionsRespVO;
import com.ontos.module.catalog.controller.admin.asset.vo.SemanticAssetRespVO;
import com.ontos.module.catalog.controller.admin.asset.vo.SemanticAssetRevisionReqVO;
import com.ontos.module.catalog.controller.admin.asset.vo.SemanticAssetSaveReqVO;
import com.ontos.module.catalog.controller.admin.asset.vo.SemanticAssetValidateReqVO;
import com.ontos.module.catalog.service.asset.SemanticAssetDraftValidator;
import com.ontos.module.catalog.service.asset.SemanticAssetLifecycleService;
import com.ontos.module.catalog.service.asset.SemanticAssetOptionsService;
import com.ontos.module.catalog.support.InMemorySemanticAssetRepository;
import com.ontos.module.catalog.service.asset.SemanticAssetRevisionService;
import com.ontos.module.catalog.service.asset.SemanticAssetSynthesisService;
import com.ontos.module.catalog.support.InMemorySemanticAssetRepository;
import com.ontos.module.catalog.support.StubPublishService;
import com.ontos.module.catalog.support.TestManifests;
import com.ontos.module.ontology.service.publish.PublishGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.ontos.framework.common.exception.util.ServiceExceptionUtil.exception;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 语义资产接口（B1/W4）：7 端点 happy path 与管理面闸门拒绝。 */
class SemanticAssetControllerTest {

    private InMemorySemanticAssetRepository repository;
    private SemanticAssetDraftValidator validator;
    private SemanticAssetController controller;

    @BeforeEach
    void setUp() {
        repository = new InMemorySemanticAssetRepository();
        validator = new SemanticAssetDraftValidator(
                repository, TestManifests.manifestService(TestManifests.activeV22()));
        controller = new SemanticAssetController(
                new SemanticAssetLifecycleService(repository, validator), validator, optionsService(),
                synthesisService(), revisionService(), allowGuard());
    }

    @Test
    void shouldCompleteDraftFlow() {
        SemanticAssetRespVO created = controller.create("metric", createReq(metricDraft("GMV 改名")))
                .getData();
        assertEquals("draft", created.getStatus());
        assertEquals(0L, created.getRevision());

        SemanticAssetRespVO saved = controller.save("metric", "metric.gmv",
                saveReq(metricDraft("GMV 二改"), 0L)).getData();
        assertEquals(1L, saved.getRevision());

        assertTrue(controller.validate("metric", "metric.gmv", null).getData().isValid());

        SemanticAssetRespVO submitted = controller.submitReview("metric", "metric.gmv",
                revisionReq(1L)).getData();
        assertEquals("reviewing", submitted.getStatus());

        SemanticAssetRespVO withdrawn = controller.withdrawReview("metric", "metric.gmv",
                revisionReq(2L)).getData();
        assertEquals("draft", withdrawn.getStatus());
        assertEquals(3L, withdrawn.getRevision());
    }

    @Test
    void shouldExposeOptionsFromActiveManifest() {
        SemanticAssetOptionsRespVO options = controller.options().getData();

        assertEquals(6, options.getConcepts().size());
        assertEquals(23, options.getProperties().size());
        assertEquals(6, options.getDimensions().size());
        assertEquals(9, options.getMetrics().size());
        assertTrue(options.getProperties().stream()
                .anyMatch(property -> "concept.order".equals(property.getConceptId())));
    }

    @Test
    void shouldRejectWhenManagementGateDenied() {
        PublishGuard denied = new PublishGuard() {
            @Override
            public void requirePublishAllowed(String assetId) {
                throw exception(GlobalErrorCodeConstants.FORBIDDEN);
            }

            @Override
            public String currentPublisher() {
                return "tester";
            }
        };
        SemanticAssetController guarded = new SemanticAssetController(
                new SemanticAssetLifecycleService(repository, validator), validator, optionsService(),
                synthesisService(), revisionService(), denied);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> guarded.create("metric", createReq(metricDraft("GMV"))));
        assertEquals(GlobalErrorCodeConstants.FORBIDDEN.getCode(), ex.getCode());
    }

    @Test
    void shouldRejectServerOwnedFieldsInBody() {
        SemanticAssetCreateReqVO request = createReq(metricDraft("GMV"));
        request.getUnknownFields().put("owner", "attacker");

        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.create("metric", request));
        assertEquals(1_005_000_107, ex.getCode());
        assertTrue(ex.getMessage().contains("owner"), ex.getMessage());
    }

    @Test
    void shouldRejectValidateWithMismatchedId() {
        SemanticAssetValidateReqVO request = new SemanticAssetValidateReqVO();
        request.setDefinition(JsonUtils.parseMap(metricDraft("GMV")));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.validate("metric", "metric.paid_gmv", request));
        assertEquals(1_005_000_108, ex.getCode());
    }

    @Test
    void shouldCollectUnknownFieldsFromJsonBody() {
        SemanticAssetCreateReqVO request = JsonUtils.parseObject(
                "{\"definition\":{\"id\":\"metric.gmv\"},\"owner\":\"attacker\",\"status\":\"published\"}",
                SemanticAssetCreateReqVO.class);

        assertEquals("attacker", request.getUnknownFields().get("owner"));
        assertEquals("published", request.getUnknownFields().get("status"));
    }

    private static SemanticAssetRevisionService revisionService() {
        return new SemanticAssetRevisionService(new InMemorySemanticAssetRepository(),
                TestManifests.manifestService(TestManifests.activeV22()));
    }

    private static SemanticAssetSynthesisService synthesisService() {
        return new SemanticAssetSynthesisService(new InMemorySemanticAssetRepository(),
                TestManifests.manifestService(TestManifests.activeV22()), new StubPublishService());
    }

    private static SemanticAssetOptionsService optionsService() {
        return new SemanticAssetOptionsService(
                TestManifests.manifestService(TestManifests.activeV22()));
    }

    private static PublishGuard allowGuard() {
        return new PublishGuard() {
            @Override
            public void requirePublishAllowed(String assetId) {
            }

            @Override
            public String currentPublisher() {
                return "tester";
            }
        };
    }

    private static SemanticAssetCreateReqVO createReq(String definitionJson) {
        SemanticAssetCreateReqVO request = new SemanticAssetCreateReqVO();
        request.setDefinition(JsonUtils.parseMap(definitionJson));
        return request;
    }

    private static SemanticAssetRevisionReqVO revisionReq(Long expectedRevision) {
        SemanticAssetRevisionReqVO request = new SemanticAssetRevisionReqVO();
        request.setExpectedRevision(expectedRevision);
        return request;
    }

    private static SemanticAssetSaveReqVO saveReq(String definitionJson, Long expectedRevision) {
        SemanticAssetSaveReqVO request = new SemanticAssetSaveReqVO();
        request.setDefinition(definitionJson == null ? null : JsonUtils.parseMap(definitionJson));
        request.setExpectedRevision(expectedRevision);
        return request;
    }

    private static String metricDraft(String name) {
        return """
                {"id":"metric.gmv","name":"%s","summary":"s","aliases":["a"],
                 "objectId":"concept.order","propertyId":"property.order.amount","aggregation":"SUM",
                 "allowedModes":["QUERY"],"allowedDimensions":["dim.region"],"sensitive":false}
                """.formatted(name);
    }
}
