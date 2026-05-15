package com.termux.app.hermes.fragments;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

public class PlaceholderTerminalFragment extends Fragment {
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        // This fragment is a placeholder - the actual terminal is shown via TerminalView visibility toggle
        // This fragment should never actually be visible
        FrameLayout frame = new FrameLayout(requireContext());
        return frame;
    }
}
