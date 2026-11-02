# EV routing for the Hyundai Creta Electric India

**Creta-specific, right-hand drive.** An [HMG-API](https://github.com/sirishant/HMG-API/tree/creta-su2iev)
mod for the Creta Electric sold in India (`SU2IEV.IND`, software `260430`) and nothing else. It
refuses to load on any other car, region or software. There is no guarantee, and you use it at
your own risk.

It gives Google Maps through Android Auto the car's battery state, so Maps can show battery on
arrival and plan charging. Turn-by-turn on the cluster isn't here: that's `wireless-creta-rhd` in
the HMG-API fork. The energy model is checked against city and town driving, with very little
above 90 km/h.

There's no download. The project this comes from has no licence yet, so its build can't be shared.

**Build:** link this folder to `mods/ev-routing-creta` in the HMG-API fork, then:

    ./build.sh mods
    sh tools/mkmodpkg.sh mods/ev-routing-creta <your mod key>

`test.sh` runs the off-car checks. Tests and `tools/` don't go in the package.

The [install guide](https://github.com/sirishant/HMG-API/blob/creta-su2iev/docs/creta/README.md)
covers the order: this mod is Stage 5, after wireless Android Auto works.
