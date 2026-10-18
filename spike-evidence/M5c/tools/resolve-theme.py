import json,sys,re
def flat(d,p=""):
    for k,v in d.items():
        if k.startswith("$"): continue
        if isinstance(v,dict) and "$value" not in v and not ("value" in v and "unit" in v) and not "hex" in v: yield from flat(v,p+k+".")
        else: yield p+k, (v["$value"] if isinstance(v,dict) and "$value" in v else v)
def load(files):
    t={}
    for f in files:
        for k,v in flat(json.load(open(f))): t[k]=v
    return t
def res(t,v,depth=0):
    if isinstance(v,str) and re.fullmatch(r"\{[^}]+\}",v): return res(t,t[v[1:-1]],depth+1)
    if isinstance(v,dict) and "hex" in v: 
        h=v["hex"]; a=v.get("alpha",1); return h if a==1 else f"{h}@{a}"
    if isinstance(v,dict) and "value" in v and "unit" in v: return f"{v['value']}{v['unit']}"
    if isinstance(v,list): return [res(t,x) for x in v]
    if isinstance(v,dict): return {k:res(t,x) for k,x in v.items()}
    return v
def theme(brand,mode):
    r=json.load(open("product/theme/resolver.json"))
    files=[s for st in r["sets"] for s in st["sources"]]+r["modifiers"][0]["contexts"][brand]+[x.replace("{brand}",brand) for x in r["modifiers"][1]["contexts"][mode]]
    t=load(files); return {k:res(t,v) for k,v in t.items()}
if __name__=="__main__":
    out={m:theme("eudiw_v1",m) for m in ["light","high-contrast","dark","dark-high-contrast"]}
    json.dump(out,open(sys.argv[1],"w"),indent=1)
