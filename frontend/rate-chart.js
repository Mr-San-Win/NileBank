// Numbers below position display-only SVG coordinates; never settle money.
export function createRateChart({ api, getUser }) {
  const $ = s => document.querySelector(s);
  let days = 30, sequence = 0;
  const ns = "http://www.w3.org/2000/svg";
  function svg(name, attrs, text) {
    const el = document.createElementNS(ns, name);
    for (const [key,value] of Object.entries(attrs)) el.setAttribute(key, String(value));
    if (text !== undefined) el.textContent = text;
    return el;
  }
  const rate = value => new Intl.NumberFormat(undefined, { maximumSignificantDigits: 7 }).format(value);
  function clear() {
    $("#fx-chart-plot").replaceChildren(); $("#fx-chart-rows").replaceChildren();
    $("#fx-chart-data").hidden = true; $("#fx-chart-rate").textContent = "—";
    $("#fx-chart-change").textContent = "";
  }
  async function load() {
    const ticket = ++sequence; clear();
    const from = $("#fx-from").value, to = $("#fx-to").value;
    if (from === to) { $("#fx-chart-status").textContent = "Choose two different currencies to see their history."; return; }
    $("#fx-chart-status").textContent = "Loading published rate history…";
    $("#fx-chart-plot").setAttribute("aria-busy", "true");
    try {
      const data = await api(`/exchange/history?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}&days=${days}`);
      if (ticket !== sequence || !getUser()) return;
      const points = data.points.map(p => ({date:p.date, value:Number(p.rate)}));
      if (!points.length || points.some(p => !Number.isFinite(p.value) || p.value <= 0)) throw new Error("History is unavailable for this pair.");
      const first = points[0], last = points.at(-1), change = (last.value / first.value - 1) * 100;
      $("#fx-chart-rate").textContent = `1 ${from} = ${rate(last.value)} ${to}`;
      const badge = $("#fx-chart-change"); badge.textContent = `${change > 0 ? "+" : ""}${change.toFixed(2)}% over published period`;
      badge.className = change < 0 ? "fx-change down" : "fx-change";
      $("#fx-chart-status").textContent = `${data.provider} · ${first.date} – ${last.date} · ${points.length} publications. ${points.length === 1 ? "Only one publication is available." : "Axis is scaled to this period; missing publication dates are not invented."}`;
      const chart = svg("svg", {viewBox:"0 0 900 270",role:"img","aria-label":`${from} to ${to} daily reference rate history, ${first.date} to ${last.date}`});
      chart.append(svg("title",{},`${from}/${to}: ${rate(first.value)} to ${rate(last.value)}. Change ${change.toFixed(2)} percent.`));
      const values = points.map(p=>p.value), low = Math.min(...values), high = Math.max(...values);
      const padding = Math.max((high-low)*0.15, high*0.0001), min = low-padding, max = high+padding;
      const left=16,right=770,top=20,bottom=225;
      const start=Date.parse(first.date), end=Date.parse(last.date);
      const x=p=>left+(end===start ? (right-left)/2 : (Date.parse(p.date)-start)/(end-start)*(right-left));
      const y=p=>bottom-(p.value-min)/(max-min)*(bottom-top);
      for(let i=0;i<4;i++) {
        const at=top+i*(bottom-top)/3;
        chart.append(svg("line",{x1:left,x2:right,y1:at,y2:at,stroke:"#e6eaf1","stroke-dasharray":"4 5"}));
        chart.append(svg("text",{x:right+14,y:at+4,fill:"#52647a","font-size":13},rate(max-i*(max-min)/3)));
      }
      const path=points.map((p,i)=>`${i ? "L":"M"} ${x(p)} ${y(p)}`).join(" ");
      chart.append(svg("path",{d:path+` L ${x(last)} ${bottom} L ${x(first)} ${bottom} Z`,fill:"#255bec",opacity:"0.07"}));
      chart.append(svg("path",{d:path,fill:"none",stroke:"#255bec","stroke-width":3,"stroke-linejoin":"round"}));
      for(const p of points) {
        const dot=svg("circle",{cx:x(p),cy:y(p),r:4,fill:"#255bec",tabindex:0});
        dot.append(svg("title",{},`${p.date}: 1 ${from} = ${rate(p.value)} ${to}`)); chart.append(dot);
        const row=document.createElement("tr");
        for(const value of [p.date,`1 ${from} = ${rate(p.value)} ${to}`]) { const cell=document.createElement("td");cell.textContent=value;row.append(cell); }
        $("#fx-chart-rows").append(row);
      }
      chart.append(svg("text",{x:left,y:258,fill:"#52647a","font-size":13},first.date));
      chart.append(svg("text",{x:right,y:258,fill:"#52647a","font-size":13,"text-anchor":"end"},last.date));
      $("#fx-chart-plot").replaceChildren(chart); $("#fx-chart-data").hidden=false;
      $("#fx-chart-unit").textContent=`${to} per 1 ${from}`;
    } catch(e) {
      if (ticket === sequence && getUser()) $("#fx-chart-status").textContent = e.message + " No fabricated history is shown.";
    } finally { if(ticket===sequence) $("#fx-chart-plot").setAttribute("aria-busy","false"); }
  }
  document.querySelectorAll("[data-fx-days]").forEach(button=>button.addEventListener("click",()=>{
    days=Number(button.dataset.fxDays);
    document.querySelectorAll("[data-fx-days]").forEach(b=>b.setAttribute("aria-pressed", String(b===button)));
    void load();
  }));
  return { load, reset() { sequence++;clear();$("#fx-chart-plot").setAttribute("aria-busy","false"); } };
}
