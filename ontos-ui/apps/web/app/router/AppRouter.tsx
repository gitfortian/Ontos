import { Navigate, Route, Routes } from "react-router-dom";

import { AskPage } from "@/app/ask/page";
import { ApprovalsPage } from "@/app/approvals/page";
import { AuditPage } from "@/app/audit/page";
import { AssetsPage } from "@/app/ontology/assets/page";
import { DiffPage } from "@/app/ontology/diff/page";
import { ManifestsPage } from "@/app/ontology/manifests/page";
import { RecordsPage } from "@/app/ontology/records/page";
import { CopilotKitProbePage } from "@/app/probe/copilotkit/page";

import AppLayout from "../layout/AppLayout";

export default function AppRouter() {
  return (
    <Routes>
      <Route element={<AppLayout />}>
        <Route path="/" element={<Navigate replace to="/ask" />} />
        <Route path="/ask" element={<AskPage />} />
        <Route path="/ontology/manifests" element={<ManifestsPage />} />
        <Route path="/ontology/assets" element={<AssetsPage />} />
        <Route path="/ontology/diff" element={<DiffPage />} />
        <Route path="/ontology/records" element={<RecordsPage />} />
        <Route path="/approvals" element={<ApprovalsPage />} />
        <Route path="/audit" element={<AuditPage />} />
        {/* T5.5 验证页：CopilotKit Headless 直连 AG-UI SSE，验完可移除 */}
        <Route path="/probe/copilotkit" element={<CopilotKitProbePage />} />
        <Route path="*" element={<Navigate replace to="/ask" />} />
      </Route>
    </Routes>
  );
}
