using System.Diagnostics;
using System.Net.Http;
using System.Text;
using System.Text.Json;
using NAudio.Wave;

namespace BondBassHaptics;

internal static class Program
{
    [STAThread]
    private static void Main()
    {
        Application.ThreadException += (_, args) => LogCrash(args.Exception);
        AppDomain.CurrentDomain.UnhandledException += (_, args) =>
        {
            if (args.ExceptionObject is Exception ex)
            {
                LogCrash(ex);
            }
        };

        try
        {
            ApplicationConfiguration.Initialize();
            Application.Run(new BassForm());
        }
        catch (Exception ex)
        {
            LogCrash(ex);
            MessageBox.Show(ex.Message, "Bond Bass Haptics crashed", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
    }

    private static void LogCrash(Exception ex)
    {
        try
        {
            File.AppendAllText(Path.Combine(AppContext.BaseDirectory, "BondBassHaptics-crash.log"),
                $"{DateTime.Now:O}{Environment.NewLine}{ex}{Environment.NewLine}{Environment.NewLine}");
        }
        catch
        {
        }
    }
}

internal sealed class BassSettings
{
    public string PhoneHost { get; set; } = "127.0.0.1";
    public int PhonePort { get; set; } = 7878;
    public bool AutoStartCapture { get; set; }
    public bool EnableHaptics { get; set; } = true;
    public int Sensitivity { get; set; } = 65;
    public int BassCutoffHz { get; set; } = 140;
    public int TriggerLevel { get; set; } = 18;
    public int BeatThresholdPercent { get; set; } = 128;
    public int CooldownMs { get; set; } = 95;
    public int MaxPulsesPerTenSeconds { get; set; } = 30;
    public int MinStrength { get; set; } = 18;
    public int MaxStrength { get; set; } = 82;
    public int PulseDurationMs { get; set; } = 65;
    public string Pattern { get; set; } = "single";
    public bool ColorsEnabled { get; set; } = true;
    public string Color { get; set; } = "#37B8FF";
}

internal sealed class BassForm : Form
{
    private static readonly JsonSerializerOptions JsonOptions = new() { WriteIndented = true };
    private readonly HttpClient http = new() { Timeout = TimeSpan.FromMilliseconds(350) };
    private readonly object audioLock = new();
    private readonly Queue<DateTime> recentPulses = new();

    private readonly TextBox phoneHost = new();
    private readonly NumericUpDown phonePort = new();
    private readonly CheckBox autoStartCapture = new();
    private readonly CheckBox enableHaptics = new();
    private readonly NumericUpDown sensitivity = new();
    private readonly NumericUpDown bassCutoff = new();
    private readonly NumericUpDown triggerLevel = new();
    private readonly NumericUpDown beatThreshold = new();
    private readonly NumericUpDown cooldown = new();
    private readonly NumericUpDown pulseLimit = new();
    private readonly NumericUpDown minStrength = new();
    private readonly NumericUpDown maxStrength = new();
    private readonly NumericUpDown pulseDuration = new();
    private readonly ComboBox pattern = new();
    private readonly CheckBox colorsEnabled = new();
    private readonly TextBox color = new();
    private readonly Label status = new();
    private readonly Label liveLabel = new();
    private readonly Label deviceLabel = new();
    private readonly ProgressBar bassMeter = new();
    private readonly TextBox logBox = new();
    private readonly System.Windows.Forms.Timer uiTimer = new();

    private BassSettings settings = new();
    private WasapiLoopbackCapture? capture;
    private DateTime lastPulse = DateTime.MinValue;
    private double lowPass;
    private double fastBass;
    private double slowBass = 10.0;
    private double latestBass;
    private double latestStrength;
    private bool sendInFlight;

    public BassForm()
    {
        Text = "Bond Bass Haptics";
        Width = 860;
        Height = 650;
        MinimumSize = new Size(780, 560);
        Font = new Font("Segoe UI", 9F);

        settings = LoadSettings();
        BuildUi();
        ApplySettingsToUi();
        WireLiveSettings();

        uiTimer.Interval = 50;
        uiTimer.Tick += (_, _) => RefreshMeters();
        uiTimer.Start();

        FormClosing += (_, _) =>
        {
            SaveSettingsFromUi();
            StopCapture();
        };

        Shown += (_, _) =>
        {
            if (settings.AutoStartCapture)
            {
                StartCapture();
            }
        };
    }

    private void BuildUi()
    {
        var root = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 1,
            RowCount = 6,
            Padding = new Padding(12),
        };
        root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        root.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        Controls.Add(root);

        var header = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 2, AutoSize = true };
        header.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        header.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        var title = new Label { Text = "Bond Bass Haptics", AutoSize = true, Font = new Font(Font.FontFamily, 18F, FontStyle.Bold) };
        status.Text = "Stopped";
        status.AutoSize = true;
        status.Anchor = AnchorStyles.Right;
        header.Controls.Add(title, 0, 0);
        header.Controls.Add(status, 1, 0);
        root.Controls.Add(header, 0, 0);

        var connection = GridBox("Phone connection", 7, 2);
        AddLabel(connection.Grid, "Phone host", 0, 0);
        phoneHost.Dock = DockStyle.Fill;
        connection.Grid.Controls.Add(phoneHost, 1, 0);
        AddLabel(connection.Grid, "Phone port", 2, 0);
        SetupNumber(phonePort, 1, 65535, 7878, 82);
        connection.Grid.Controls.Add(phonePort, 3, 0);
        connection.Grid.Controls.Add(Button("Save", SaveSettingsFromUi), 4, 0);
        connection.Grid.Controls.Add(Button("ADB Forward", AdbForward), 5, 0);
        connection.Grid.Controls.Add(Button("Send Test", SendTest), 6, 0);
        var phoneHint = new Label
        {
            Text = "Use 127.0.0.1 with ADB Forward, or put your phone IP here if both devices are on Wi-Fi.",
            AutoSize = true,
            ForeColor = Color.FromArgb(71, 84, 103),
            Padding = new Padding(0, 6, 0, 0),
            Dock = DockStyle.Fill,
        };
        connection.Grid.Controls.Add(phoneHint, 0, 1);
        connection.Grid.SetColumnSpan(phoneHint, 7);
        root.Controls.Add(connection.Box, 0, 1);

        var audio = GridBox("Audio capture", 5, 2);
        enableHaptics.Text = "Enable bass vibrations";
        enableHaptics.AutoSize = true;
        audio.Grid.Controls.Add(enableHaptics, 0, 0);
        autoStartCapture.Text = "Start capture when app opens";
        autoStartCapture.AutoSize = true;
        audio.Grid.Controls.Add(autoStartCapture, 1, 0);
        audio.Grid.Controls.Add(Button("Start Listening", StartCapture), 2, 0);
        audio.Grid.Controls.Add(Button("Stop", StopCapture), 3, 0);
        audio.Grid.Controls.Add(Button("Reset Defaults", ResetDefaults), 4, 0);
        deviceLabel.Text = "Output device: Windows default output";
        deviceLabel.AutoSize = true;
        deviceLabel.ForeColor = Color.FromArgb(71, 84, 103);
        audio.Grid.Controls.Add(deviceLabel, 0, 1);
        audio.Grid.SetColumnSpan(deviceLabel, 5);
        root.Controls.Add(audio.Box, 0, 2);

        var detection = GridBox("Bass detection", 8, 2);
        AddLabel(detection.Grid, "Sensitivity", 0, 0);
        SetupNumber(sensitivity, 1, 200, 65, 70);
        detection.Grid.Controls.Add(sensitivity, 1, 0);
        AddLabel(detection.Grid, "Bass cutoff Hz", 2, 0);
        SetupNumber(bassCutoff, 40, 300, 140, 70);
        detection.Grid.Controls.Add(bassCutoff, 3, 0);
        AddLabel(detection.Grid, "Trigger level", 4, 0);
        SetupNumber(triggerLevel, 1, 100, 18, 70);
        detection.Grid.Controls.Add(triggerLevel, 5, 0);
        AddLabel(detection.Grid, "Beat threshold %", 6, 0);
        SetupNumber(beatThreshold, 105, 250, 128, 70);
        detection.Grid.Controls.Add(beatThreshold, 7, 0);
        AddLabel(detection.Grid, "Cooldown ms", 0, 1);
        SetupNumber(cooldown, 45, 2000, 95, 70);
        detection.Grid.Controls.Add(cooldown, 1, 1);
        AddLabel(detection.Grid, "Max pulses / 10 sec", 2, 1);
        SetupNumber(pulseLimit, 1, 80, 30, 70);
        detection.Grid.Controls.Add(pulseLimit, 3, 1);
        liveLabel.Text = "Live bass: 0";
        liveLabel.AutoSize = true;
        liveLabel.Anchor = AnchorStyles.Left;
        detection.Grid.Controls.Add(liveLabel, 4, 1);
        bassMeter.Minimum = 0;
        bassMeter.Maximum = 100;
        bassMeter.Dock = DockStyle.Fill;
        detection.Grid.Controls.Add(bassMeter, 5, 1);
        detection.Grid.SetColumnSpan(bassMeter, 3);
        root.Controls.Add(detection.Box, 0, 3);

        var output = GridBox("Haptic output", 8, 1);
        AddLabel(output.Grid, "Pattern", 0, 0);
        pattern.DropDownStyle = ComboBoxStyle.DropDownList;
        pattern.Items.AddRange(new object[] { "single", "double", "triple", "warning", "ramp", "rise", "heartbeat" });
        pattern.Width = 100;
        output.Grid.Controls.Add(pattern, 1, 0);
        AddLabel(output.Grid, "Min strength", 2, 0);
        SetupNumber(minStrength, 0, 100, 18, 70);
        output.Grid.Controls.Add(minStrength, 3, 0);
        AddLabel(output.Grid, "Max strength", 4, 0);
        SetupNumber(maxStrength, 0, 100, 82, 70);
        output.Grid.Controls.Add(maxStrength, 5, 0);
        AddLabel(output.Grid, "Pulse duration ms", 6, 0);
        SetupNumber(pulseDuration, 10, 600, 65, 70);
        output.Grid.Controls.Add(pulseDuration, 7, 0);
        colorsEnabled.Text = "Send color";
        colorsEnabled.AutoSize = true;
        output.Grid.Controls.Add(colorsEnabled, 0, 1);
        AddLabel(output.Grid, "Color hex", 1, 1);
        color.Width = 90;
        output.Grid.Controls.Add(color, 2, 1);
        var outputHint = new Label
        {
            Text = "Lower cooldown feels tighter. Higher trigger level and beat threshold make it less spammy.",
            AutoSize = true,
            ForeColor = Color.FromArgb(71, 84, 103),
            Anchor = AnchorStyles.Left,
        };
        output.Grid.Controls.Add(outputHint, 3, 1);
        output.Grid.SetColumnSpan(outputHint, 5);
        root.Controls.Add(output.Box, 0, 4);

        logBox.Dock = DockStyle.Fill;
        logBox.Multiline = true;
        logBox.ScrollBars = ScrollBars.Vertical;
        logBox.ReadOnly = true;
        logBox.Font = new Font("Consolas", 10F);
        root.Controls.Add(logBox, 0, 5);
    }

    private static (GroupBox Box, TableLayoutPanel Grid) GridBox(string text, int columns, int rows)
    {
        var box = new GroupBox { Text = text, Dock = DockStyle.Top, AutoSize = true, Padding = new Padding(8), Margin = new Padding(0, 8, 0, 0) };
        var grid = new TableLayoutPanel { Dock = DockStyle.Top, ColumnCount = columns, RowCount = rows, AutoSize = true };
        for (var i = 0; i < columns; i++)
        {
            grid.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        }
        box.Controls.Add(grid);
        return (box, grid);
    }

    private static void AddLabel(TableLayoutPanel grid, string text, int column, int row)
    {
        grid.Controls.Add(new Label { Text = text, AutoSize = true, Anchor = AnchorStyles.Left, Padding = new Padding(0, 6, 4, 0), Margin = new Padding(4) }, column, row);
    }

    private static void SetupNumber(NumericUpDown control, int min, int max, int value, int width)
    {
        control.Minimum = min;
        control.Maximum = max;
        control.Value = value;
        control.Width = width;
        control.Margin = new Padding(4);
    }

    private static Button Button(string text, Action action)
    {
        var button = new Button { Text = text, AutoSize = true, MinimumSize = new Size(108, 30), Margin = new Padding(4) };
        button.Click += (_, _) => action();
        return button;
    }

    private BassSettings LoadSettings()
    {
        try
        {
            var path = SettingsPath();
            if (File.Exists(path))
            {
                return JsonSerializer.Deserialize<BassSettings>(File.ReadAllText(path)) ?? new BassSettings();
            }
        }
        catch
        {
        }
        return new BassSettings();
    }

    private void ApplySettingsToUi()
    {
        phoneHost.Text = settings.PhoneHost;
        phonePort.Value = Clamp(settings.PhonePort, 1, 65535);
        autoStartCapture.Checked = settings.AutoStartCapture;
        enableHaptics.Checked = settings.EnableHaptics;
        sensitivity.Value = Clamp(settings.Sensitivity, 1, 200);
        bassCutoff.Value = Clamp(settings.BassCutoffHz, 40, 300);
        triggerLevel.Value = Clamp(settings.TriggerLevel, 1, 100);
        beatThreshold.Value = Clamp(settings.BeatThresholdPercent, 105, 250);
        cooldown.Value = Clamp(settings.CooldownMs, 45, 2000);
        pulseLimit.Value = Clamp(settings.MaxPulsesPerTenSeconds, 1, 80);
        minStrength.Value = Clamp(settings.MinStrength, 0, 100);
        maxStrength.Value = Clamp(settings.MaxStrength, 0, 100);
        pulseDuration.Value = Clamp(settings.PulseDurationMs, 10, 600);
        pattern.SelectedItem = pattern.Items.Contains(settings.Pattern) ? settings.Pattern : "single";
        colorsEnabled.Checked = settings.ColorsEnabled;
        color.Text = string.IsNullOrWhiteSpace(settings.Color) ? "#37B8FF" : settings.Color;
    }

    private void WireLiveSettings()
    {
        EventHandler update = (_, _) => settings = CurrentSettings();
        phoneHost.TextChanged += update;
        phonePort.ValueChanged += update;
        autoStartCapture.CheckedChanged += update;
        enableHaptics.CheckedChanged += update;
        sensitivity.ValueChanged += update;
        bassCutoff.ValueChanged += update;
        triggerLevel.ValueChanged += update;
        beatThreshold.ValueChanged += update;
        cooldown.ValueChanged += update;
        pulseLimit.ValueChanged += update;
        minStrength.ValueChanged += update;
        maxStrength.ValueChanged += update;
        pulseDuration.ValueChanged += update;
        pattern.SelectedIndexChanged += update;
        colorsEnabled.CheckedChanged += update;
        color.TextChanged += update;
    }

    private void SaveSettingsFromUi()
    {
        settings = CurrentSettings();
        File.WriteAllText(SettingsPath(), JsonSerializer.Serialize(settings, JsonOptions));
        Log($"Saved {SettingsPath()}");
    }

    private BassSettings CurrentSettings()
    {
        if (InvokeRequired)
        {
            return settings;
        }
        var min = (int)minStrength.Value;
        var max = Math.Max(min, (int)maxStrength.Value);
        settings = new BassSettings
        {
            PhoneHost = string.IsNullOrWhiteSpace(phoneHost.Text) ? "127.0.0.1" : phoneHost.Text.Trim(),
            PhonePort = (int)phonePort.Value,
            AutoStartCapture = autoStartCapture.Checked,
            EnableHaptics = enableHaptics.Checked,
            Sensitivity = (int)sensitivity.Value,
            BassCutoffHz = (int)bassCutoff.Value,
            TriggerLevel = (int)triggerLevel.Value,
            BeatThresholdPercent = (int)beatThreshold.Value,
            CooldownMs = (int)cooldown.Value,
            MaxPulsesPerTenSeconds = (int)pulseLimit.Value,
            MinStrength = min,
            MaxStrength = max,
            PulseDurationMs = (int)pulseDuration.Value,
            Pattern = pattern.SelectedItem?.ToString() ?? "single",
            ColorsEnabled = colorsEnabled.Checked,
            Color = color.Text.Trim(),
        };
        return settings;
    }

    private void ResetDefaults()
    {
        settings = new BassSettings();
        ApplySettingsToUi();
        SaveSettingsFromUi();
        Log("Defaults restored.");
    }

    private void StartCapture()
    {
        if (capture != null)
        {
            return;
        }

        try
        {
            SaveSettingsFromUi();
            lock (audioLock)
            {
                lowPass = 0;
                fastBass = 0;
                slowBass = 10;
                latestBass = 0;
                latestStrength = 0;
            }
            capture = new WasapiLoopbackCapture();
            capture.DataAvailable += CaptureDataAvailable;
            capture.RecordingStopped += (_, args) =>
            {
                if (args.Exception != null)
                {
                    Log($"Audio capture stopped: {args.Exception.Message}");
                }
            };
            deviceLabel.Text = $"Output device: {capture.WaveFormat.SampleRate} Hz, {capture.WaveFormat.Channels} channel(s)";
            capture.StartRecording();
            status.Text = "Listening to PC audio";
            Log("Started Windows output capture.");
        }
        catch (Exception ex)
        {
            capture = null;
            status.Text = "Stopped";
            MessageBox.Show(ex.Message, "Could not start audio capture", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
    }

    private void StopCapture()
    {
        var old = capture;
        capture = null;
        if (old == null)
        {
            return;
        }
        try
        {
            old.StopRecording();
            old.Dispose();
        }
        catch
        {
        }
        status.Text = "Stopped";
        Log("Stopped audio capture.");
    }

    private void CaptureDataAvailable(object? sender, WaveInEventArgs args)
    {
        var localCapture = capture;
        if (localCapture == null || args.BytesRecorded <= 0)
        {
            return;
        }

        var localSettings = CurrentSettings();
        var level = AnalyzeBass(args.Buffer, args.BytesRecorded, localCapture.WaveFormat, localSettings);
        var now = DateTime.UtcNow;
        var shouldPulse = false;
        var strength = 0;
        var duration = localSettings.PulseDurationMs;

        lock (audioLock)
        {
            fastBass = fastBass * 0.62 + level * 0.38;
            slowBass = slowBass * 0.985 + level * 0.015;
            latestBass = Math.Max(latestBass * 0.82, fastBass);

            while (recentPulses.Count > 0 && (now - recentPulses.Peek()).TotalSeconds > 10)
            {
                recentPulses.Dequeue();
            }

            var cooldownReady = (now - lastPulse).TotalMilliseconds >= localSettings.CooldownMs;
            var beatReady = fastBass >= localSettings.TriggerLevel
                && fastBass >= Math.Max(localSettings.TriggerLevel, slowBass * localSettings.BeatThresholdPercent / 100.0);
            var strongSustain = fastBass >= Math.Min(100, localSettings.TriggerLevel + 28);
            shouldPulse = localSettings.EnableHaptics
                && cooldownReady
                && recentPulses.Count < localSettings.MaxPulsesPerTenSeconds
                && (beatReady || strongSustain);

            if (shouldPulse)
            {
                var intensity = Math.Clamp((fastBass - localSettings.TriggerLevel) / Math.Max(1.0, 100.0 - localSettings.TriggerLevel), 0.0, 1.0);
                strength = localSettings.MinStrength + (int)Math.Round((localSettings.MaxStrength - localSettings.MinStrength) * intensity);
                duration = localSettings.PulseDurationMs + (int)Math.Round(35 * intensity);
                latestStrength = strength;
                lastPulse = now;
                recentPulses.Enqueue(now);
            }
        }

        if (shouldPulse && strength > 0)
        {
            _ = SendPulseAsync(localSettings, strength, duration, level);
        }
    }

    private double AnalyzeBass(byte[] buffer, int bytesRecorded, WaveFormat format, BassSettings localSettings)
    {
        var bytesPerSample = Math.Max(1, format.BitsPerSample / 8);
        var frameBytes = bytesPerSample * Math.Max(1, format.Channels);
        if (frameBytes <= 0)
        {
            return 0;
        }

        var cutoff = Math.Clamp(localSettings.BassCutoffHz, 40, 300);
        var alpha = 1.0 - Math.Exp(-2.0 * Math.PI * cutoff / Math.Max(1, format.SampleRate));
        var sum = 0.0;
        var frames = 0;

        lock (audioLock)
        {
            for (var offset = 0; offset + frameBytes <= bytesRecorded; offset += frameBytes)
            {
                var mono = 0.0;
                for (var channel = 0; channel < format.Channels; channel++)
                {
                    mono += ReadSample(buffer, offset + channel * bytesPerSample, format);
                }
                mono /= Math.Max(1, format.Channels);
                lowPass += alpha * (mono - lowPass);
                sum += lowPass * lowPass;
                frames++;
            }
        }

        if (frames == 0)
        {
            return 0;
        }

        var rms = Math.Sqrt(sum / frames);
        var sensitivityScale = 0.45 + localSettings.Sensitivity / 42.0;
        return Math.Clamp(rms * 1000.0 * sensitivityScale, 0.0, 100.0);
    }

    private static double ReadSample(byte[] buffer, int offset, WaveFormat format)
    {
        try
        {
            return format.Encoding switch
            {
                WaveFormatEncoding.IeeeFloat when format.BitsPerSample == 32 => BitConverter.ToSingle(buffer, offset),
                WaveFormatEncoding.Extensible when format.BitsPerSample == 32 => BitConverter.ToSingle(buffer, offset),
                WaveFormatEncoding.Pcm when format.BitsPerSample == 16 => BitConverter.ToInt16(buffer, offset) / 32768.0,
                WaveFormatEncoding.Pcm when format.BitsPerSample == 24 => Read24Bit(buffer, offset) / 8388608.0,
                WaveFormatEncoding.Pcm when format.BitsPerSample == 32 => BitConverter.ToInt32(buffer, offset) / 2147483648.0,
                WaveFormatEncoding.Pcm when format.BitsPerSample == 8 => (buffer[offset] - 128) / 128.0,
                _ => 0.0,
            };
        }
        catch
        {
            return 0.0;
        }
    }

    private static int Read24Bit(byte[] buffer, int offset)
    {
        var value = buffer[offset] | (buffer[offset + 1] << 8) | (buffer[offset + 2] << 16);
        if ((value & 0x800000) != 0)
        {
            value |= unchecked((int)0xFF000000);
        }
        return value;
    }

    private async Task SendPulseAsync(BassSettings localSettings, int strength, int duration, double bassLevel)
    {
        if (sendInFlight)
        {
            return;
        }
        sendInFlight = true;
        try
        {
            var payload = new Dictionary<string, object?>
            {
                ["source"] = "bond_bass_haptics",
                ["final"] = true,
                ["event"] = "audio_bass",
                ["pattern"] = localSettings.Pattern,
                ["strength"] = strength,
                ["duration"] = duration,
                ["cooldown"] = Math.Max(0, localSettings.CooldownMs - 10),
                ["priority"] = 74,
                ["bass"] = Math.Round(bassLevel, 1),
            };
            if (localSettings.ColorsEnabled)
            {
                payload["color"] = NormalizeColor(localSettings.Color);
            }
            var json = JsonSerializer.Serialize(payload);
            using var content = new StringContent(json, Encoding.UTF8, "application/json");
            using var response = await http.PostAsync($"http://{localSettings.PhoneHost}:{localSettings.PhonePort}/event", content);
            if (!response.IsSuccessStatusCode)
            {
                Log($"Phone returned HTTP {(int)response.StatusCode}");
            }
        }
        catch (Exception ex)
        {
            Log($"Phone send failed: {ex.Message}");
        }
        finally
        {
            sendInFlight = false;
        }
    }

    private void AdbForward()
    {
        var adb = FindAdb();
        if (adb == null)
        {
            MessageBox.Show("Could not find adb.exe. Put this app beside the BondCraft portable folder, or install Android platform-tools.", "ADB not found", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return;
        }
        var port = (int)phonePort.Value;
        var result = RunProcess(adb, $"forward tcp:{port} tcp:{port}", TimeSpan.FromSeconds(8));
        if (result.ExitCode != 0)
        {
            MessageBox.Show(result.Output, "ADB forward failed", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return;
        }
        phoneHost.Text = "127.0.0.1";
        SaveSettingsFromUi();
        Log($"ADB forward active: PC 127.0.0.1:{port} -> phone 127.0.0.1:{port}");
    }

    private void SendTest()
    {
        var localSettings = CurrentSettings();
        _ = SendPulseAsync(localSettings, Math.Max(35, localSettings.MinStrength), localSettings.PulseDurationMs, 50);
        Log("Sent test pulse.");
    }

    private string? FindAdb()
    {
        var appDir = AppContext.BaseDirectory;
        var candidates = new List<string>
        {
            Path.Combine(appDir, "tools", "platform-tools", "adb.exe"),
            Path.Combine(appDir, "platform-tools", "adb.exe"),
            Path.Combine(appDir, "adb.exe"),
        };

        var current = new DirectoryInfo(appDir);
        for (var i = 0; current != null && i < 7; i++, current = current.Parent)
        {
            candidates.Add(Path.Combine(current.FullName, "tools", "platform-tools", "adb.exe"));
            candidates.Add(Path.Combine(current.FullName, "06_minecraft_bridge", "dist", "BondCraft-Haptics-Portable", "tools", "platform-tools", "adb.exe"));
        }

        foreach (var candidate in candidates.Distinct(StringComparer.OrdinalIgnoreCase))
        {
            if (File.Exists(candidate))
            {
                return candidate;
            }
        }

        return Environment.GetEnvironmentVariable("PATH")?
            .Split(Path.PathSeparator)
            .Select(path => Path.Combine(path, "adb.exe"))
            .FirstOrDefault(File.Exists);
    }

    private static ProcessResult RunProcess(string file, string arguments, TimeSpan timeout)
    {
        try
        {
            using var process = new Process();
            process.StartInfo = new ProcessStartInfo
            {
                FileName = file,
                Arguments = arguments,
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
            };
            process.Start();
            if (!process.WaitForExit((int)timeout.TotalMilliseconds))
            {
                process.Kill(true);
                return new ProcessResult(-1, "Timed out.");
            }
            var output = (process.StandardOutput.ReadToEnd() + "\r\n" + process.StandardError.ReadToEnd()).Trim();
            return new ProcessResult(process.ExitCode, string.IsNullOrWhiteSpace(output) ? "(no output)" : output);
        }
        catch (Exception ex)
        {
            return new ProcessResult(-1, ex.Message);
        }
    }

    private void RefreshMeters()
    {
        double level;
        double strength;
        lock (audioLock)
        {
            level = latestBass;
            strength = latestStrength;
        }
        var intLevel = Clamp((int)Math.Round(level), 0, 100);
        bassMeter.Value = intLevel;
        liveLabel.Text = $"Live bass: {intLevel}   Last strength: {(int)Math.Round(strength)}";
    }

    private static string NormalizeColor(string value)
    {
        var clean = value.Trim();
        if (!clean.StartsWith("#", StringComparison.Ordinal))
        {
            clean = "#" + clean;
        }
        return clean.Length == 7 ? clean : "#37B8FF";
    }

    private string SettingsPath() => Path.Combine(AppContext.BaseDirectory, "bass-settings.json");

    private void Log(string message)
    {
        if (IsDisposed)
        {
            return;
        }
        if (InvokeRequired)
        {
            BeginInvoke(() => Log(message));
            return;
        }
        logBox.AppendText($"{DateTime.Now:HH:mm:ss}  {message}{Environment.NewLine}");
    }

    private static int Clamp(int value, int min, int max) => Math.Max(min, Math.Min(max, value));

    private sealed record ProcessResult(int ExitCode, string Output);
}
