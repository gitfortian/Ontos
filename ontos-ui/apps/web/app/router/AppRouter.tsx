import { Navigate, Route, Routes } from "react-router-dom";

import { AskPage } from "@/app/ask/page";
import { ApprovalsPage } from "@/app/approvals/page";
import { AuditPage } from "@/app/audit/page";
import { DataSourcesPage } from "@/app/data/sources/page";
import { DebugPage } from "@/app/debug/page";
import { AssetsPage } from "@/app/ontology/assets/page";
import { DefinitionsPage } from "@/app/ontology/definitions/page";
import { DiffPage } from "@/app/ontology/diff/page";
import { ManifestsPage } from "@/app/ontology/manifests/page";
import { ModelingPage } from "@/app/ontology/modeling/page";
import { RecordsPage } from "@/app/ontology/records/page";
import { CopilotKitProbePage } from "@/app/probe/copilotkit/page";
import { ProductPrototypePage } from "@/app/prototype/page";

import AppLayout from "../layout/AppLayout";

export default function AppRouter() {
  return (
    <Routes>
      {/* 默认入口是真实控制台；设计原型保留在 /prototype，避免启动后误判功能范围 */}
      <Route path="/" element={<Navigate replace to="/ask" />} />
      <Route path="/prototype" element={<ProductPrototypePage />} />
      <Route element={<AppLayout />}>
        <Route path="/ask" element={<AskPage />} />
        <Route path="/data/sources" element={<DataSourcesPage />} />
        <Route path="/ontology/modeling" element={<ModelingPage />} />
        <Route path="/ontology/manifests" element={<ManifestsPage />} />
        <Route path="/ontology/assets" element={<AssetsPage />} />
        <Route path="/ontology/definitions" element={<DefinitionsPage />} />
        <Route path="/ontology/diff" element={<DiffPage />} />
        <Route path="/ontology/records" element={<RecordsPage />} />
        <Route path="/approvals" element={<ApprovalsPage />} />
        <Route path="/audit" element={<AuditPage />} />
        <Route path="/debug" element={<DebugPage />} />
        {/* T5.5 验证页：CopilotKit Headless 直连 AG-UI SSE，验完可移除 */}
        <Route path="/probe/copilotkit" element={<CopilotKitProbePage />} />
        <Route path="*" element={<Navigate replace to="/ask" />} />
      </Route>
    </Routes>
  );
}
