function Resolve-WorkbenchInstallationState {
    param([Parameter(Mandatory)][string]$WorkbenchRoot, [string]$RequestedDirectory)

    $configured = [Environment]::GetEnvironmentVariable('WORKBENCH_INSTALLATION_STATE_DIRECTORY')
    if ($null -eq $configured) {
        if ($RequestedDirectory) { return $RequestedDirectory }
        return Join-Path $WorkbenchRoot '.workbench'
    }

    $comparison = if ($IsWindows) { [StringComparison]::OrdinalIgnoreCase } else { [StringComparison]::Ordinal }
    try {
        if (-not [IO.Path]::IsPathFullyQualified($configured) -or ($IsWindows -and $configured -match '^[\\/]{2}')) {
            throw 'Non-local or relative installation path.'
        }
        $directory = [IO.Path]::TrimEndingDirectorySeparator([IO.Path]::GetFullPath($configured))
        $volumeRoot = [IO.Path]::GetPathRoot($directory)
        if ($directory.Equals($volumeRoot, $comparison) -or ($IsWindows -and [IO.DriveInfo]::new($volumeRoot).DriveType -eq 'Network')) {
            throw 'Unsupported installation volume.'
        }
        $checkout = [IO.Path]::TrimEndingDirectorySeparator([IO.Path]::GetFullPath($WorkbenchRoot))
        $separator = [IO.Path]::DirectorySeparatorChar
        if ($directory.Equals($checkout, $comparison) -or $directory.StartsWith($checkout + $separator, $comparison) -or
            $checkout.StartsWith($directory + $separator, $comparison)) {
            throw 'Installation state overlaps the checkout.'
        }
        foreach ($path in @($directory, $checkout)) {
            for ($ancestor = [IO.DirectoryInfo]::new($path); $null -ne $ancestor; $ancestor = $ancestor.Parent) {
                if (-not $ancestor.Exists -or ($ancestor.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
                    throw 'Invalid installation or checkout directory.'
                }
            }
        }
    } catch {
        throw 'WORKBENCH_INSTALLATION_STATE_DIRECTORY must name an existing, absolute local directory outside the checkout. Use physical paths for both directories.'
    }

    if ($RequestedDirectory) {
        try {
            $requested = [IO.Path]::TrimEndingDirectorySeparator([IO.Path]::GetFullPath($RequestedDirectory))
            $matches = [IO.Path]::IsPathFullyQualified($RequestedDirectory) -and $requested.Equals($directory, $comparison)
        } catch {
            $matches = $false
        }
        if (-not $matches) { throw 'StateDirectory conflicts with the configured installation state directory.' }
    }
    return $directory
}
