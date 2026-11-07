import ReactDOM from "react-dom/client";

import App from "./app/App";
import { ThemeProvider } from "./context/theme-context";
import "./app/styles/global.css";

ReactDOM.createRoot(document.getElementById("root")!).render(
  <ThemeProvider>
    <App />
  </ThemeProvider>,
);
