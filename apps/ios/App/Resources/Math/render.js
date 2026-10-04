(function () {
  function show(node) {
    var out = document.getElementById("out");
    out.textContent = "";
    out.appendChild(node);
  }

  function failed(source) {
    var out = document.getElementById("out");
    out.textContent = source;
  }

  window.renderMath = function (source, display) {
    try {
      var html = katex.renderToString(source, {
        throwOnError: false,
        displayMode: !!display,
        trust: false,
      });
      var holder = document.createElement("div");
      holder.innerHTML = html;
      show(holder);
    } catch (error) {
      failed(source);
    }
  };

  var diagramSerial = 0;

  window.renderMermaid = function (source) {
    try {
      diagramSerial += 1;
      mermaid.initialize({ startOnLoad: false, securityLevel: "strict" });
      mermaid.render("dshDiagram" + diagramSerial, source).then(function (result) {
        var holder = document.createElement("div");
        holder.innerHTML = result.svg;
        show(holder);
      }).catch(function () {
        failed(source);
      });
    } catch (error) {
      failed(source);
    }
  };
})();
