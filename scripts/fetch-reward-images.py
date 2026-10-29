#!/usr/bin/env python3
"""Download unmodified catalogue reference photos; no runtime network dependency."""
import hashlib
import json
import pathlib
import urllib.request
ROOT = pathlib.Path(__file__).resolve().parents[1] / 'src/main/resources/static/media/rewards'
ASSETS = [
 ('red-cap.png', 'https://beechfield.com/products/b10', 'https://d2csxpduxe849s.cloudfront.net/media/BBD46EA3-F7E7-441D-8663C747FAEA082D/B917028F-26C0-4EE5-989F8FC7ACF26A36/A7FF2A5F-BECA-45D9-86B9FDB454A7DF7D/webimage-51EFE05B-6468-4D7E-9A06B883E262AAFF.png', 'Beechfield'),
 ('red-shirt.jpg', 'https://www.gildan.se/en/gi5000-en-red', 'https://www.gildan.se/sites/default/files/styles/product-node/public/5000_199c_040_g2025_ld_0.jpg?itok=4-AzlhNS', 'Gildan / Trendmark'),
 ('red-hoodie.jpg', 'https://www.gildan.se/en/gi18500-en-red', 'https://www.gildan.se/sites/default/files/styles/product-node/public/18500_199c_040_g2025_ld_0.jpg?itok=tnmyMCh1', 'Gildan / Trendmark'),
]

def main():
    downloaded=[]
    for name,page,url,owner in ASSETS:
        with urllib.request.urlopen(urllib.request.Request(url,headers={'User-Agent':'Mozilla/5.0'}),timeout=40) as r:
            data=r.read()
        if not (data.startswith(b'\xff\xd8') or data.startswith(b'\x89PNG')):
            raise ValueError('Not an image: '+url)
        downloaded.append((name,data,dict(imageUrl='/media/rewards/'+name,sourcePage=page,originalUrl=url,author=owner,license='Product reference image; copyright retained by source owner',modification='None',sha256=hashlib.sha256(data).hexdigest())))
    ROOT.mkdir(parents=True,exist_ok=True)
    prior=json.loads((ROOT/'credits.json').read_text()) if (ROOT/'credits.json').exists() else []
    credits=[c for c in prior if c.get('imageUrl') not in [a[2]['imageUrl'] for a in downloaded]]
    for name,data,credit in downloaded:
        (ROOT/name).write_bytes(data)
        credits.append(credit)
        print(name,len(data))
    (ROOT/'credits.json').write_text(json.dumps(credits,indent=2)+'\n')
if __name__=='__main__': main()
