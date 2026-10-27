using System.Diagnostics;
using System.Net;
using System.Net.Http;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;

namespace BondCraftBridge;

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
            Application.Run(new BridgeForm());
        }
        catch (Exception ex)
        {
            LogCrash(ex);
            MessageBox.Show(ex.Message, "BondCraft Bridge crashed", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
    }

    private static void LogCrash(Exception ex)
    {
        try
        {
            var baseDir = AppContext.BaseDirectory;
            var parent = Directory.GetParent(Path.TrimEndingDirectorySeparator(baseDir))?.FullName;
            var packageDir = parent != null && (Directory.Exists(Path.Combine(parent, "android")) || Directory.Exists(Path.Combine(parent, "tools")))
                ? parent
                : baseDir;
            File.AppendAllText(Path.Combine(packageDir, "BondCraftBridge-crash.log"),
                $"{DateTime.Now:O}{Environment.NewLine}{ex}{Environment.NewLine}{Environment.NewLine}");
        }
        catch
        {
        }
    }
}

internal sealed class BridgeSettings
{
    public string ListenHost { get; set; } = "127.0.0.1";
    public int ListenPort { get; set; } = 8765;
    public string PhoneHost { get; set; } = "127.0.0.1";
    public int PhonePort { get; set; } = 7878;
    public bool AutoStart { get; set; } = true;
}

internal sealed class BridgeForm : Form
{
    private static readonly JsonSerializerOptions JsonOptions = new() { WriteIndented = true };

    private readonly string appDir = AppContext.BaseDirectory;
    private readonly string packageDir = ResolvePackageDir(AppContext.BaseDirectory);
    private readonly TextBox listenHost = new();
    private readonly NumericUpDown listenPort = new();
    private readonly TextBox phoneHost = new();
    private readonly NumericUpDown phonePort = new();
    private readonly CheckBox autoStart = new();
    private readonly Label status = new();
    private readonly TextBox logBox = new();
    private readonly HttpClient http = new() { Timeout = TimeSpan.FromMilliseconds(900) };

    private BridgeSettings settings = new();
    private TcpListener? listener;
    private CancellationTokenSource? listenerCancel;

    public BridgeForm()
    {
        Text = "BondCraft PC Phone Bridge";
        Width = 820;
        Height = 580;
        MinimumSize = new Size(740, 480);
        Font = new Font("Segoe UI", 9F);

        settings = LoadSettings();
        BuildUi();
        ApplySettingsToUi();
        FormClosing += (_, _) => StopServer();

        Shown += (_, _) =>
        {
            if (settings.AutoStart)
            {
                StartServer();
            }
        };
    }

    private void BuildUi()
    {
        var root = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 1,
            RowCount = 4,
            Padding = new Padding(12),
        };
        root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        root.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        Controls.Add(root);

        var header = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 2, AutoSize = true };
        header.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        header.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        var title = new Label { Text = "BondCraft PC Phone Bridge", AutoSize = true, Font = new Font(Font.FontFamily, 18F, FontStyle.Bold) };
        status.Text = "Stopped";
        status.AutoSize = true;
        status.Anchor = AnchorStyles.Right;
        header.Controls.Add(title, 0, 0);
        header.Controls.Add(status, 1, 0);
        root.Controls.Add(header, 0, 0);

        var grid = new TableLayoutPanel
        {
            Dock = DockStyle.Top,
            ColumnCount = 7,
            RowCount = 3,
            AutoSize = true,
            Padding = new Padding(8),
        };
        grid.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        grid.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 45));
        grid.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        grid.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 35));
        grid.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        grid.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        grid.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        var box = new GroupBox { Text = "Bridge", Dock = DockStyle.Top, AutoSize = true, Padding = new Padding(8) };
        box.Controls.Add(grid);
        root.Controls.Add(box, 0, 1);

        AddLabel(grid, "Listen host", 0, 0);
        listenHost.Dock = DockStyle.Fill;
        grid.Controls.Add(listenHost, 1, 0);
        AddLabel(grid, "Listen port", 2, 0);
        listenPort.Minimum = 1;
        listenPort.Maximum = 65535;
        listenPort.Width = 82;
        grid.Controls.Add(listenPort, 3, 0);
        autoStart.Text = "Auto start";
        autoStart.AutoSize = true;
        grid.Controls.Add(autoStart, 4, 0);
        grid.Controls.Add(Button("Start", StartServer), 5, 0);
        grid.Controls.Add(Button("Stop", StopServer), 6, 0);

        AddLabel(grid, "Phone host", 0, 1);
        phoneHost.Dock = DockStyle.Fill;
        grid.Controls.Add(phoneHost, 1, 1);
        AddLabel(grid, "Phone port", 2, 1);
        phonePort.Minimum = 1;
        phonePort.Maximum = 65535;
        phonePort.Width = 82;
        grid.Controls.Add(phonePort, 3, 1);
        grid.Controls.Add(Button("Save", SaveSettingsFromUi), 4, 1);
        grid.Controls.Add(Button("ADB Forward", AdbForward), 5, 1);
        grid.Controls.Add(Button("Send Test", SendTest), 6, 1);

        grid.Controls.Add(new Label(), 0, 2);
        grid.Controls.Add(new Label(), 1, 2);
        grid.Controls.Add(new Label(), 2, 2);
        grid.Controls.Add(new Label(), 3, 2);
        grid.Controls.Add(new Label(), 4, 2);
        grid.Controls.Add(Button("Install APK", InstallApk), 5, 2);
        grid.Controls.Add(Button("ADB Devices", ShowAdbDevices), 6, 2);

        var hint = new Label
        {
            Text = "Minecraft owns haptic settings. This app only forwards final patterns to the phone shown in the Android app, or over ADB Forward.",
            Dock = DockStyle.Top,
            AutoSize = true,
            ForeColor = Color.FromArgb(71, 84, 103),
            Padding = new Padding(0, 6, 0, 8),
        };
        root.Controls.Add(hint, 0, 2);

        logBox.Dock = DockStyle.Fill;
        logBox.Multiline = true;
        logBox.ScrollBars = ScrollBars.Vertical;
        logBox.ReadOnly = true;
        logBox.Font = new Font("Consolas", 10F);
        root.Controls.Add(logBox, 0, 3);
    }

    private static void AddLabel(TableLayoutPanel grid, string text, int column, int row)
    {
        grid.Controls.Add(new Label { Text = text, AutoSize = true, Anchor = AnchorStyles.Left, Padding = new Padding(0, 6, 2, 0) }, column, row);
    }

    private static Button Button(string text, Action action)
    {
        var button = new Button { Text = text, AutoSize = true, MinimumSize = new Size(92, 30), Margin = new Padding(4) };
        button.Click += (_, _) => action();
        return button;
    }

    private BridgeSettings LoadSettings()
    {
        try
        {
            var path = SettingsPath();
            if (File.Exists(path))
            {
                return JsonSerializer.Deserialize<BridgeSettings>(File.ReadAllText(path)) ?? new BridgeSettings();
            }
        }
        catch
        {
        }
        return new BridgeSettings();
    }

    private void ApplySettingsToUi()
    {
        listenHost.Text = settings.ListenHost;
        listenPort.Value = settings.ListenPort;
        phoneHost.Text = settings.PhoneHost;
        phonePort.Value = settings.PhonePort;
        autoStart.Checked = settings.AutoStart;
    }

    private void SaveSettingsFromUi()
    {
        settings = new BridgeSettings
        {
            ListenHost = string.IsNullOrWhiteSpace(listenHost.Text) ? "127.0.0.1" : listenHost.Text.Trim(),
            ListenPort = (int)listenPort.Value,
            PhoneHost = string.IsNullOrWhiteSpace(phoneHost.Text) ? "127.0.0.1" : phoneHost.Text.Trim(),
            PhonePort = (int)phonePort.Value,
            AutoStart = autoStart.Checked,
        };
        File.WriteAllText(SettingsPath(), JsonSerializer.Serialize(settings, JsonOptions));
        Log($"Saved {SettingsPath()}");
    }

    private void StartServer()
    {
        if (listener != null)
        {
            return;
        }
        SaveSettingsFromUi();
        try
        {
            var ip = ResolveListenAddress(settings.ListenHost);
            listenerCancel = new CancellationTokenSource();
            listener = new TcpListener(ip, settings.ListenPort);
            listener.Start();
            status.Text = $"Listening on http://{settings.ListenHost}:{settings.ListenPort}/event";
            Log($"Listening for Minecraft events on http://{settings.ListenHost}:{settings.ListenPort}/event");
            _ = Task.Run(() => AcceptLoop(listenerCancel.Token));
        }
        catch (Exception ex)
        {
            listener = null;
            listenerCancel = null;
            status.Text = "Stopped";
            MessageBox.Show(ex.Message, "Could not start bridge", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
    }

    private void StopServer()
    {
        var server = listener;
        listener = null;
        listenerCancel?.Cancel();
        listenerCancel = null;
        try
        {
            server?.Stop();
        }
        catch
        {
        }
        status.Text = "Stopped";
    }

    private async Task AcceptLoop(CancellationToken token)
    {
        while (!token.IsCancellationRequested && listener != null)
        {
            try
            {
                var client = await listener.AcceptTcpClientAsync(token);
                _ = Task.Run(() => HandleClient(client, token), token);
            }
            catch (OperationCanceledException)
            {
                return;
            }
            catch (Exception ex)
            {
                Log($"Bridge accept failed: {ex.Message}");
            }
        }
    }

    private async Task HandleClient(TcpClient client, CancellationToken token)
    {
        using var _ = client;
        try
        {
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(token);
            timeout.CancelAfter(TimeSpan.FromSeconds(4));
            var stream = client.GetStream();
            var request = await ReadHttpRequest(stream, timeout.Token);
            if (request.Method == "GET")
            {
                await WriteResponse(stream, 200, "{\"ok\":true,\"message\":\"BondCraft PC phone bridge listening\"}", timeout.Token);
                return;
            }
            if (request.Method != "POST" || request.Path != "/event")
            {
                await WriteResponse(stream, 404, "{\"ok\":false,\"error\":\"Use POST /event\"}", timeout.Token);
                return;
            }
            var ok = await ForwardEvent(request.Body, timeout.Token);
            await WriteResponse(stream, ok ? 200 : 502, ok ? "{\"ok\":true}" : "{\"ok\":false}", timeout.Token);
        }
        catch (Exception ex)
        {
            Log($"Bridge socket error: {ex.Message}");
        }
    }

    private async Task<bool> ForwardEvent(string body, CancellationToken token)
    {
        try
        {
            var url = $"http://{phoneHost.Text.Trim()}:{(int)phonePort.Value}/event";
            using var content = new StringContent(body, Encoding.UTF8, "application/json");
            using var response = await http.PostAsync(url, content, token);
            var eventName = "?";
            try
            {
                using var doc = JsonDocument.Parse(body);
                if (doc.RootElement.TryGetProperty("event", out var eventElement))
                {
                    eventName = eventElement.GetString() ?? "?";
                }
            }
            catch
            {
            }
            if (response.IsSuccessStatusCode)
            {
                Log($"{eventName} -> phone");
                return true;
            }
            Log($"Phone returned HTTP {(int)response.StatusCode}");
        }
        catch (Exception ex)
        {
            Log($"Phone forward failed: {ex.Message}");
        }
        return false;
    }

    private void AdbForward()
    {
        var adb = FindAdb();
        if (adb == null)
        {
            MessageBox.Show("This package should include tools\\platform-tools\\adb.exe. Re-extract the full package, then try again.", "ADB not found", MessageBoxButtons.OK, MessageBoxIcon.Error);
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

    private void InstallApk()
    {
        var adb = FindAdb();
        if (adb == null)
        {
            MessageBox.Show("This package should include tools\\platform-tools\\adb.exe. Re-extract the full package, then try again.", "ADB not found", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return;
        }
        var apk = Path.Combine(packageDir, "android", "BondBandBridge-game.apk");
        if (!File.Exists(apk))
        {
            MessageBox.Show("This package should include android\\BondBandBridge-game.apk.", "APK not found", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return;
        }
        Log("Installing Android app over USB...");
        var result = RunProcess(adb, $"install -r \"{apk}\"", TimeSpan.FromSeconds(60));
        Log(result.Output);
        if (result.ExitCode != 0)
        {
            MessageBox.Show(result.Output, "Install failed", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return;
        }
        MessageBox.Show("Android app installed. Open it on the phone and connect the band.", "Installed", MessageBoxButtons.OK, MessageBoxIcon.Information);
    }

    private void ShowAdbDevices()
    {
        var adb = FindAdb();
        if (adb == null)
        {
            MessageBox.Show("This package should include tools\\platform-tools\\adb.exe. Re-extract the full package, then try again.", "ADB not found", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return;
        }
        var result = RunProcess(adb, "devices", TimeSpan.FromSeconds(10));
        Log(result.Output);
        MessageBox.Show(result.Output, "ADB Devices", MessageBoxButtons.OK, MessageBoxIcon.Information);
    }

    private void SendTest()
    {
        var payload = new
        {
            source = "pc_bridge_test",
            final = true,
            @event = "pc_test",
            player = Environment.MachineName,
            pattern = "warning",
            strength = 55,
            duration = 90,
            cooldown = 0,
            priority = 90,
            color = "#FFFFFF",
        };
        _ = Task.Run(async () =>
        {
            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(2));
            var ok = await ForwardEvent(JsonSerializer.Serialize(payload), timeout.Token);
            if (!ok)
            {
                BeginInvoke(() => MessageBox.Show("Could not reach the phone app. Check that the phone app is open and ADB Forward or phone IP is correct.", "Test failed", MessageBoxButtons.OK, MessageBoxIcon.Warning));
            }
        });
    }

    private string? FindAdb()
    {
        var candidates = new[]
        {
            Path.Combine(packageDir, "tools", "platform-tools", "adb.exe"),
            Path.Combine(packageDir, "platform-tools", "adb.exe"),
            Path.Combine(appDir, "tools", "platform-tools", "adb.exe"),
            Path.Combine(appDir, "platform-tools", "adb.exe"),
            Path.Combine(appDir, "adb.exe"),
        };
        foreach (var candidate in candidates)
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

    private static IPAddress ResolveListenAddress(string host)
    {
        if (IPAddress.TryParse(host, out var ip))
        {
            return ip;
        }
        return Dns.GetHostAddresses(host).FirstOrDefault(address => address.AddressFamily == AddressFamily.InterNetwork) ?? IPAddress.Loopback;
    }

    private static async Task<HttpRequest> ReadHttpRequest(NetworkStream stream, CancellationToken token)
    {
        var bytes = new List<byte>(8192);
        var buffer = new byte[2048];
        var headerEnd = -1;
        while (headerEnd < 0)
        {
            var read = await stream.ReadAsync(buffer, token);
            if (read <= 0)
            {
                throw new IOException("Client closed before headers.");
            }
            bytes.AddRange(buffer.AsSpan(0, read).ToArray());
            headerEnd = IndexOf(bytes, "\r\n\r\n"u8.ToArray());
            if (bytes.Count > 64 * 1024)
            {
                throw new IOException("HTTP headers too large.");
            }
        }

        var headerText = Encoding.ASCII.GetString(bytes.Take(headerEnd).ToArray());
        var lines = headerText.Split("\r\n");
        var first = lines[0].Split(' ');
        var method = first.Length > 0 ? first[0] : "";
        var path = first.Length > 1 ? first[1] : "/";
        var length = 0;
        foreach (var line in lines.Skip(1))
        {
            var split = line.Split(':', 2);
            if (split.Length == 2 && split[0].Equals("Content-Length", StringComparison.OrdinalIgnoreCase))
            {
                int.TryParse(split[1].Trim(), out length);
            }
        }

        var bodyStart = headerEnd + 4;
        while (bytes.Count < bodyStart + length)
        {
            var read = await stream.ReadAsync(buffer, token);
            if (read <= 0)
            {
                break;
            }
            bytes.AddRange(buffer.AsSpan(0, read).ToArray());
        }
        var body = length > 0 ? Encoding.UTF8.GetString(bytes.Skip(bodyStart).Take(length).ToArray()) : "{}";
        return new HttpRequest(method, path, body);
    }

    private static int IndexOf(List<byte> haystack, byte[] needle)
    {
        for (var i = 0; i <= haystack.Count - needle.Length; i++)
        {
            var found = true;
            for (var j = 0; j < needle.Length; j++)
            {
                if (haystack[i + j] != needle[j])
                {
                    found = false;
                    break;
                }
            }
            if (found)
            {
                return i;
            }
        }
        return -1;
    }

    private static async Task WriteResponse(NetworkStream stream, int code, string body, CancellationToken token)
    {
        var bodyBytes = Encoding.UTF8.GetBytes(body);
        var reason = code == 200 ? "OK" : code == 404 ? "Not Found" : "Bad Gateway";
        var header = $"HTTP/1.1 {code} {reason}\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: {bodyBytes.Length}\r\nConnection: close\r\n\r\n";
        await stream.WriteAsync(Encoding.ASCII.GetBytes(header), token);
        await stream.WriteAsync(bodyBytes, token);
    }

    private ProcessResult RunProcess(string file, string arguments, TimeSpan timeout)
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

    private static string ResolvePackageDir(string baseDir)
    {
        var parent = Directory.GetParent(Path.TrimEndingDirectorySeparator(baseDir))?.FullName;
        if (parent != null && (Directory.Exists(Path.Combine(parent, "android")) || Directory.Exists(Path.Combine(parent, "tools"))))
        {
            return parent;
        }
        return baseDir;
    }

    private string SettingsPath() => Path.Combine(packageDir, "settings.json");

    private void Log(string message)
    {
        if (InvokeRequired)
        {
            BeginInvoke(() => Log(message));
            return;
        }
        logBox.AppendText($"{DateTime.Now:HH:mm:ss}  {message}{Environment.NewLine}");
    }

    private sealed record HttpRequest(string Method, string Path, string Body);
    private sealed record ProcessResult(int ExitCode, string Output);
}
