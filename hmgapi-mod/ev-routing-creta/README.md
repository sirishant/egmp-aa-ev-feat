# EV routing for the Hyundai Creta Electric India

**Creta-specific, right-hand drive.** An [HMG-API](https://github.com/sirishant/HMG-API/tree/creta-su2iev)
mod for the Creta Electric sold in India (`SU2IEV.IND`, software `260430`) and nothing else. It
refuses to load on any other car, region or software. There is no guarantee, and you use it at
your own risk.

It gives Google Maps through Android Auto the car's battery state, so Maps can show battery on
arrival and plan charging. Turn-by-turn on the cluster isn't here: that's `wireless-creta-rhd` in
the HMG-API fork.

Shipped for this car with the permission of the egmp-aa-ev-feat author. Get the package from
[the Creta release](https://github.com/sirishant/HMG-API/releases/latest).

**Build it yourself:** link this folder to `mods/ev-routing-creta` in the HMG-API fork, then:

    ./build.sh mods
    sh tools/mkmodpkg.sh mods/ev-routing-creta <your mod key>

`test.sh` runs the off-car checks. Tests and `tools/` don't go in the package.

The [install guide](https://github.com/sirishant/HMG-API/blob/creta-su2iev/docs/creta/README.md)
covers the whole setup.
