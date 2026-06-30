plugins {
    id("name.remal.sonarlint")
}

sonarLint {
    rules {
        // println in the generated sample app trips this rule. Drop the
        // disable below once you swap in real logging.
        disable("kotlin:S106")
        // math.random is intentionally a non-cryptographic PRNG, as Lua specifies.
        disable("kotlin:S2245")
    }
}
