package com.ontos.module.ontology.service.dataview;

import java.util.List;

import com.ontos.module.ontology.core.ManifestPayload;

/**
 * 数据视图快照供给（B3）：资源侧 active 视图 → 语义侧消费（合成发布快照 / 草稿校验合并视图）。
 *
 * <p>依赖倒置：接口定义在 ontology（语义侧），实现由 datasource 提供（资源侧是事实源），
 * server 装配。catalog/ontology 不得依赖 datasource（ArchUnit 守护）。
 */
public interface DataViewSnapshotProvider {

    /** 当前 active 数据视图全量（资源事实即时生效，D1）。 */
    List<ManifestPayload.DataView> activeViews();
}
