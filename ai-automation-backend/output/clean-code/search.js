// Clean Code Website — Client-side Search
(function() {
  const chapters = [
    { title: "Kapitel 1: Sauberer Code", desc: "Einfuehrung, Qualitaet, Handwerk, was ist sauberer Code", url: "chapters/01-sauberer-code.html", keywords: "sauberer code einfuehrung qualitaet handwerk definition" },
    { title: "Kapitel 2: Aussagekraeftige Bezeichner", desc: "Namensgebung, Variablen, Klassen, Methoden, Bedeutung", url: "chapters/02-bezeichner.html", keywords: "bezeichner namen variablen klassen methoden bedeutung intention" },
    { title: "Kapitel 3: Funktionen", desc: "Kleine Funktionen, Parameter, Abstraktionsebene, eine Aufgabe", url: "chapters/03-funktionen.html", keywords: "funktionen methoden klein parameter abstraktionsebene seiteneffekte" },
    { title: "Kapitel 4: Kommentare", desc: "Gute und schlechte Kommentare, Dokumentation, selbsterklaerend", url: "chapters/04-kommentare.html", keywords: "kommentare dokumentation javadoc todo erklaerender code" },
    { title: "Kapitel 5: Formatierung", desc: "Vertikale und horizontale Formatierung, Einrueckung, Lesbarkeit", url: "chapters/05-formatierung.html", keywords: "formatierung einrueckung vertikale horizontale ordnung lesbarkeit" },
    { title: "Kapitel 6: Objekte und Datenstrukturen", desc: "Abstraktion, Kapselung, Law of Demeter", url: "chapters/06-objekte-daten.html", keywords: "objekte datenstrukturen abstraktion kapselung law of demeter" },
    { title: "Kapitel 7: Fehlerbehandlung", desc: "Exceptions, Try-Catch, Null vermeiden, robuster Code", url: "chapters/07-fehlerbehandlung.html", keywords: "fehlerbehandlung exceptions try catch null fehler ausnahmen" },
    { title: "Kapitel 8: Grenzen", desc: "Drittanbieter-Code, API-Grenzen, Adapter-Pattern", url: "chapters/08-grenzen.html", keywords: "grenzen boundaries drittanbieter api integration adapter" },
    { title: "Kapitel 9: Unit Tests", desc: "TDD, FIRST-Prinzipien, saubere Tests", url: "chapters/09-unit-tests.html", keywords: "unit tests tdd testgetrieben first sauber tests" },
    { title: "Kapitel 10: Klassen", desc: "SRP, Kohaesion, Klassenorganisation", url: "chapters/10-klassen.html", keywords: "klassen srp single responsibility kohaesion organisation" },
    { title: "Kapitel 11: Systeme", desc: "Architektur, Dependency Injection, Trennung von Belangen", url: "chapters/11-systeme.html", keywords: "systeme architektur trennung belange dependency injection aspekte" },
    { title: "Kapitel 12: Emergenz", desc: "Vier Regeln einfachen Designs, Kent Beck", url: "chapters/12-emergenz.html", keywords: "emergenz design einfachheit vier regeln kent beck" },
    { title: "Kapitel 13: Nebenlaeufigkeit", desc: "Threads, Synchronisation, Parallelitaet", url: "chapters/13-nebenlaeufigkeit.html", keywords: "nebenlaeufigkeit concurrency threads synchronisation parallelitaet" },
    { title: "Kapitel 14: Sukzessive Verfeinerung", desc: "Refactoring, iterativ, Args-Beispiel", url: "chapters/14-verfeinerung.html", keywords: "verfeinerung sukzessive refactoring iterativ args beispiel" },
    { title: "Kapitel 15: JUnit — Interna", desc: "JUnit-Framework Refactoring, Fallstudie", url: "chapters/15-junit.html", keywords: "junit test framework refactoring interna verbesserung" },
    { title: "Kapitel 16: SerialDate — Refactoring", desc: "Praktische Fallstudie, Analyse und Ueberarbeitung", url: "chapters/16-serialdate.html", keywords: "serialdate refactoring praxis fallstudie analyse verbesserung" },
    { title: "Kapitel 17: Code-Gerueche & Heuristiken", desc: "Katalog von Code-Geruechen und Heuristiken", url: "chapters/17-code-gerueche.html", keywords: "code gerueche smells heuristiken anti-pattern refactoring katalog" },
    { title: "Zitate", desc: "Die wichtigsten Zitate und Passagen aus Clean Code", url: "zitate.html", keywords: "zitate quotes passagen sprueche weisheiten robert martin" }
  ];

  function escapeHtml(str) {
    return str.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
  }

  const input = document.getElementById('search-input');
  const resultsDiv = document.getElementById('search-results');
  if (!input || !resultsDiv) return;

  input.addEventListener('input', function() {
    const q = this.value.toLowerCase().trim();
    resultsDiv.innerHTML = '';
    if (q.length < 2) return;

    const matches = chapters.filter(ch => {
      const haystack = (ch.title + ' ' + ch.desc + ' ' + ch.keywords).toLowerCase();
      return q.split(/\s+/).every(word => haystack.includes(word));
    });

    if (matches.length === 0) {
      resultsDiv.innerHTML = '<div class="bg-white rounded-xl shadow-lg p-4 text-sm text-gray-500">Keine Ergebnisse fuer &laquo;' + escapeHtml(q) + '&raquo;</div>';
      return;
    }

    let html = '<div class="bg-white rounded-xl shadow-lg divide-y divide-gray-100 overflow-hidden">';
    matches.forEach(ch => {
      html += '<a href="' + escapeHtml(ch.url) + '" class="block px-4 py-3 hover:bg-brand-50 transition">';
      html += '<div class="font-semibold text-sm text-gray-900">' + escapeHtml(ch.title) + '</div>';
      html += '<div class="text-xs text-gray-500 mt-0.5">' + escapeHtml(ch.desc) + '</div>';
      html += '</a>';
    });
    html += '</div>';
    resultsDiv.innerHTML = html;
  });
})();
