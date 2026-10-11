/* App adapter only. All TeX parsing and glyph layout are upstream MathJax.
 * Native evaluateJavascript polls bounded results; no JavaScript bridge. */
'use strict';
(() => {
  const root = 'https://appassets.androidplatform.net/markdown-math/';
  const extensions = ['ams', 'newcommand', 'configmacros', 'color', 'boldsymbol',
    'mathtools', 'cancel', 'enclose', 'mhchem', 'textmacros', 'braket', 'bbox', 'amscd', 'upgreek'];
  let state = {kind: 'loading'};
  let running = false;
  let outputBytes = 0;
  window.MathJax = {
    loader: {
      paths: {mathjax: root + 'mathjax', fonts: root + 'fonts',
        'mathjax-newcm': root + 'fonts/mathjax-newcm-font'},
      load: ['input/tex-base', 'output/svg', 'ui/safe', ...extensions.map(x => '[tex]/' + x)],
      failed: () => { state = {kind: 'unavailable'}; }
    },
    startup: {
      typeset: false,
      pageReady: () => { state = {kind: 'ready'}; return Promise.resolve(); }
    },
    tex: {
      packages: ['base', ...extensions],
      maxBuffer: 16384,
      maxMacros: 1000,
      maxTemplateSubstitutions: 1000,
      tags: 'none',
      formatError: (jax, error) => { throw error; }
    },
    output: {
      font: 'mathjax-newcm',
      fontPath: '[fonts]/%%FONT%%-font',
      displayOverflow: 'overflow'
    },
    svg: {fontCache: 'local', useXlink: true},
    options: {
      safeOptions: {allow: {URLs: 'none', classes: 'none', cssIDs: 'none', styles: 'safe'}}
    }
  };
  // Official standalone SVG styles: docs.mathjax.org/en/latest/web/convert.html
  // Defaults use the actual native theme color; author colors remain in children.
  const svgCss = '[data-mml-node="merror"]>g{fill:red;stroke:red}' +
    '[data-mml-node="merror"]>rect[data-background]{fill:yellow;stroke:none}' +
    '[data-frame],[data-line]{stroke-width:70px;fill:none}' +
    '.mjx-dashed{stroke-dasharray:140}' +
    '.mjx-dotted{stroke-linecap:round;stroke-dasharray:0,140}' +
    'use[data-c]{stroke-width:3px}';
  const ns = 'http://www.w3.org/2000/svg';
  const finite = x => Number.isFinite(x);
  const ex = value => {
    if (typeof value !== 'string' || !/^-?(?:\d+(?:\.\d*)?|\.\d+)ex$/.test(value)) throw Error('geometry');
    return Number.parseFloat(value);
  };
  window.NfbMath = Object.freeze({
    read: () => state,
    begin: (tex, display, metrics) => {
      if (running || state.kind !== 'ready' || typeof tex !== 'string' || tex.length > 16384 ||
          typeof display !== 'boolean' || !/^#[0-9a-f]{6}$/i.test(metrics.foregroundRgb)) return false;
      running = true;
      state = {kind: 'pending'};
      MathJax.tex2svgPromise(tex, {
        display, em: metrics.emPx, ex: metrics.exPx,
        containerWidth: metrics.containerWidthPx, scale: 1,
        family: 'sans-serif'
      }).then(container => {
        const svg = container.querySelector('svg');
        if (!svg || svg.querySelector('[data-mml-node="merror"]') || !svg.hasAttribute('viewBox')) throw Error('invalid');
        const widthPx = ex(svg.getAttribute('width')) * metrics.exPx;
        const heightPx = ex(svg.getAttribute('height')) * metrics.exPx;
        const depthPx = -ex(svg.style.verticalAlign) * metrics.exPx;
        const ascentPx = heightPx - depthPx;
        if (![widthPx, heightPx, ascentPx, depthPx].every(finite) || widthPx <= 0 || heightPx <= 0 ||
            ascentPx < 0 || depthPx < 0 || widthPx > 8192 || heightPx > 8192 || widthPx * heightPx > 4194304) throw Error('budget');
        const defs = svg.querySelector('defs') || svg.insertBefore(document.createElementNS(ns, 'defs'), svg.firstChild);
        const style = document.createElementNS(ns, 'style');
        style.textContent = svgCss;
        defs.appendChild(style);
        const g = svg.querySelector('g');
        g.setAttribute('fill', metrics.foregroundRgb);
        g.setAttribute('stroke', metrics.foregroundRgb);
        // Native decoders must not interpret ex using a different default font.
        svg.setAttribute('width', String(widthPx));
        svg.setAttribute('height', String(heightPx));
        svg.removeAttribute('style');
        svg.removeAttribute('role');
        svg.removeAttribute('focusable');
        svg.removeAttribute('aria-hidden');
        // The fixed package allowlist excludes HTML, URL and require macros.
        if (svg.querySelector('script,image,foreignObject,a') ||
            Array.from(svg.querySelectorAll('use')).some(x => {
              const href = x.getAttribute('href') || x.getAttribute('xlink:href') || '';
              return !href.startsWith('#');
            })) throw Error('invalid');
        for (const element of [svg, ...svg.querySelectorAll('*')]) {
          for (const attribute of Array.from(element.attributes)) {
            if (/^on/i.test(attribute.name) ||
                (/href$/i.test(attribute.name) && !attribute.value.startsWith('#')) ||
                (/url\s*\(/i.test(attribute.value) && !/^url\(#[A-Za-z0-9_.:-]+\)$/.test(attribute.value))) throw Error('invalid');
          }
        }
        const serialized = new XMLSerializer().serializeToString(svg);
        // JS UTF-8 bound precedes the evaluateJavascript crossing and native parse.
        const bytes = new TextEncoder().encode(serialized).length;
        if (bytes > 524288 || outputBytes + bytes > 8388608) throw Error('budget');
        outputBytes += bytes;
        state = {kind: 'rendered', svg: serialized, widthPx, heightPx, ascentPx, depthPx};
      }).catch(() => { state = {kind: 'failed'}; }).finally(() => { running = false; });
      return true;
    },
    consume: () => { if (!running) state = {kind: 'ready'}; }
  });
})();
