# Reading a recorded case report

Run the [historical Stripe case](stripe-case-runner.md) and open the printed
`report.html` path in a browser. The file works offline and can be copied on its
own for reading. Keep its companion `report.json` for the machine-readable result
and file inventory. A run directory without that JSON completion marker is
incomplete, even if an HTML file exists.

## Reading order

1. **Comparisons:** the original consumer's SDK upgrade and the upstream historical
   patch are each assessed against the same baseline. Neither is called a verified
   repair. A complete assessment lists its observed regressions; an unresolved
   assessment shows blocking issues while preserving any demonstrated regressions.
2. **Scenario coverage:** each cell says Passed, Failed or Unresolved. A passing
   process is insufficient: the corresponding behavioral assertion must have been
   evaluated against the exact declared inputs. Missing, duplicate, stale and
   incomplete observations cannot produce a passing cell.
3. **Evidence:** select a scenario, then expand its section to inspect expected
   behavior, recorded effects and ordered request/state-change events. Expand
   individual variant output only when the detailed fields are needed. Missing or
   ambiguous supporting details are labeled explicitly.
4. **Exact inputs and assessment records:** expand the technical section for source,
   configuration, expectations, fixture, harness and environment identities,
   comparison definitions and execution observations.
5. **Scope and limits:** the report covers isolated historical consumer methods,
   real SDK code and synthetic responses. It does not describe a live Stripe
   account, a complete Kill Bill deployment or a validated repair.

The normal case has five passing baseline checks, one passing and four failing
upgrade checks, and three passing and two failing historical-patch checks. In the
patch, saved-card deactivation without recorded absence evidence remains a failure
even when the resulting list happens to match the expected list.

## History, trust and sharing

The timestamp marks the recorded execution, not a live status. The report does
not inspect the current checkout or automatically determine whether it is stale.
Changed inputs require a new run. An HTML file, a hash or a green cell is not a
signature, an authorization or a release approval.

The view is generated directly from the runner's definitions and observations;
it independently invokes the domain comparator rather than trusting supplied
conclusion labels. There is no arbitrary report-upload feature. Supporting output
is presented as recorded data, not executable content or agent reasoning.

All dynamic text is HTML-escaped. The file uses native links and disclosure
controls, no JavaScript, no forms, no fonts or assets from external services, and
a Content Security Policy that permits only its exact embedded stylesheet.
Viewing it makes no application network requests. The renderer does not verify
the retained evidence inventory; the hashes remain available in `report.json`.

The current case uses synthetic identifiers. Future cases may contain private
source or execution data, so inspect report contents before sharing. CI retains
both HTML and JSON for 14 days; it does not retain the full evidence bundle.
Maven `clean` removes local reports. Copy evidence that must survive a clean build.

## Interface verification

After generating a report:

- Confirm both comparison conclusions and all fifteen coverage cells agree with
  `report.json`, including the observed regressions and recorded timestamp.
- Follow a scenario link, expand its disclosure, and open each variant's recorded
  output. Check the saved legacy-card scenario and the confirmed-removal scenario;
  both should expose the historical patch's unsupported deactivation.
- Navigate links and disclosures with Tab, and open a focused disclosure with
  Enter or Space. Focus should remain visible. Text labels accompany every color.
- At a narrow window width, cards and evidence columns stack. The comparison table
  scrolls within its labeled region; other content should fit the viewport.
- Expand relevant evidence before printing. Closed disclosures are marked as
  collapsed in the printout; printed output does not include their hidden contents.

Automated checks cover real-run rendering and inventory identity, unresolved
worker output, missing/duplicate/stale observations, undeclared scenarios,
incompatible comparison layouts and escaped malicious output. They do not replace
visual and keyboard checks in a browser.
