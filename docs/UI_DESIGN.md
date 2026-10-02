# Workspace UI Design

## References

FleetTruth remains an independent hackathon prototype. These public references informed workflow organization, not a reproduction of proprietary dashboards or a claim of affiliation:

- [Motorq Fleet Management Portal](https://www.motorq.com/products/fleet-management-portal): fleet health, priority events, filtering and drill-downs informed the overview and operational hierarchy.
- [Samsara dashboard menus](https://kb.samsara.com/hc/en-us/articles/48621492984589-Dashboard-Menus): persistent navigation and role-aware modules informed workspace organization.
- [Geotab account preferences](https://support.geotab.com/product-updates/mygeotab-options-evolved-more-organized-personalized-and-compliance-ready): grouped account preferences informed the tabbed profile.

## Visual System

The operations workspace uses restrained white/gray day surfaces and charcoal night surfaces, teal actions, amber warnings, red critical states, and categorical OEM colors. Semantic CSS variables cover text, surfaces, borders, controls, chart axes and tooltips. Compact type, 8px maximum card corners, unframed page sections and aligned data surfaces prioritize scanning over decorative effects.

The existing overview, vehicles, alerts, integrations, mappings, recovery, analytics and audit workflows remain intact. Tablet layouts collapse metric columns and shorten top-bar context; mobile navigation uses the existing drawer. The profile fills the mobile viewport and keeps its header and sign-out action visible while its contents scroll.

## Account and Appearance

- Both the top-bar avatar and sidebar account open the same profile drawer. Initials derive from the authenticated display name.
- Account shows display name from the authenticated login response, plus email, tenant and roles from `GET /api/auth/me`.
- Access displays permissions derived from the returned roles. Backend authorization remains authoritative; presentation does not grant access.
- Preferences offers Day, Night and System. `fleettruth-theme` is a browser-local preference, not a server-side account setting. Explicit choices override OS appearance, System follows OS changes, and open tabs synchronize through storage events.
- Regional values show the browser timezone/language and canonical telemetry units. These are read-only, not editable account preferences.
- Profile loading and failed-read states include a retry action. Sign-out clears the session, account UI and query cache. Credentials and tokens are never shown in the profile.
- Tabs support arrow keys, Home and End. The shared modal behavior traps focus, handles Escape and returns focus to the launcher. Reduced-motion preferences disable animation.

Account editing, avatar uploads, password changes and server-synchronized preferences are not implemented; there are no placeholder save controls implying otherwise.

## Verification Scope

`web/tests/appearance.spec.ts` covers both profile launchers, keyboard tabs and focus restoration, engineer/viewer/admin role presentation, logout, profile retry, theme reload persistence, OS changes and cross-tab synchronization. Both themes are exercised across all eight views at 390px, 768px and 1242px. Checks include page overflow, metric text clipping, JavaScript errors and a 4.5:1 minimum for the four primary text tokens against the card surface. This token check is not a full accessibility audit.

Screenshots of the overview, account and preferences are saved under `web/test-results/screenshots`. The full browser suite additionally exercises the existing fleet workflows. Run the suite against the local API and web server, then inspect `evidence/browser-tests.json` for the latest result. The older product recording does not include this UI refinement.
