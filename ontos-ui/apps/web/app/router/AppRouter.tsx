import { Navigate, Route, Routes } from "react-router-dom";

import { AskPage } from "@/app/ask/page";
import { ApprovalsPage } from "@/app/approvals/page";
import { AuditPage } from "@/app/audit/page";
import { ManifestsPage } from "@/app/ontology/manifests/page";

import AppLayout from "../layout/AppLayout";

export default function AppRouter() {
  return (
    <Routes>
      <Route element={<AppLayout />}>
        <Route path="/" element={<Navigate replace to="/ask" />} />
        <Route path="/ask" element={<AskPage />} />
        <Route path="/ontology/manifests" element={<ManifestsPage />} />
        <Route path="/approvals" element={<ApprovalsPage />} />
        <Route path="/audit" element={<AuditPage />} />
        <Route path="*" element={<Navigate replace to="/ask" />} />
      </Route>
    </Routes>
  );
}
