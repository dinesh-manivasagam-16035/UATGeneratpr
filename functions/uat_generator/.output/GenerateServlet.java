import com.catalyst.advanced.CatalystAdvancedIOHandler;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Catalyst Advanced I/O entry point. Implements CatalystAdvancedIOHandler so Catalyst
 * calls runner() for every request. Routes to the appropriate com.uat.generator.* servlet.
 */
public class GenerateServlet implements CatalystAdvancedIOHandler {

    @Override
    public void runner(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        String path = resolvePath(req);
        HttpServlet servlet = route(path);
        if (servlet != null) {
            servlet.service(req, resp);
        } else {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Not found: " + path);
        }
    }

    private static String resolvePath(HttpServletRequest req) {
        // Prefer servletPath; fall back to stripping contextPath from requestURI
        String sp = req.getServletPath();
        if (sp != null && !sp.isEmpty()) return sp;
        String uri = req.getRequestURI();
        String ctx = req.getContextPath();
        if (ctx != null && !ctx.isEmpty() && uri.startsWith(ctx)) {
            String rel = uri.substring(ctx.length());
            return rel.isEmpty() ? "/" : rel;
        }
        return uri;
    }

    private static HttpServlet route(String path) {
        if (path.startsWith("/crm/status"))    return new com.uat.generator.CrmConsentServlet();
        if (path.startsWith("/crm/connect"))   return new com.uat.generator.CrmConnectServlet();
        if (path.startsWith("/crm/auth"))      return new com.uat.generator.CrmAuthServlet();
        if (path.startsWith("/crm/callback"))  return new com.uat.generator.CrmCallbackServlet();
        if (path.startsWith("/generate"))      return new com.uat.generator.GenerateServlet();
        if (path.startsWith("/push"))          return new com.uat.generator.PushServlet();
        if (path.startsWith("/report-bug"))    return new com.uat.generator.ReportBugServlet();
        if (path.startsWith("/execute"))       return new com.uat.generator.ExecuteServlet();
        if (path.startsWith("/modules"))       return new com.uat.generator.ModulesServlet();
        if (path.startsWith("/analyze"))       return new com.uat.generator.AnalyzeServlet();
        if (path.startsWith("/functions/list")) return new com.uat.generator.FunctionsListServlet();
        if (path.startsWith("/functions/run"))  return new com.uat.generator.FunctionRunServlet();
        if (path.startsWith("/health"))        return new com.uat.generator.HealthServlet();
        return null;
    }
}
