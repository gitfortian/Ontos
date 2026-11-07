import { ToastProvider } from "@ontos/ui";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { BrowserRouter } from "react-router-dom";

import AppRouter from "./router/AppRouter";

const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });

export default function App() {
  return (
    <ToastProvider>
      <QueryClientProvider client={queryClient}>
        <BrowserRouter>
          <AppRouter />
        </BrowserRouter>
      </QueryClientProvider>
    </ToastProvider>
  );
}
