/*
 * Password Management Servlets (PWM)
 * http://www.pwm-project.org
 *
 * Copyright (c) 2006-2009 Novell, Inc.
 * Copyright (c) 2009-2017 The PWM Project
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package password.pwm.http.servlet.admin;

import lombok.Builder;
import lombok.Getter;
import password.pwm.Permission;
import password.pwm.bean.UserInfoBean;
import password.pwm.config.profile.ProfileType;
import password.pwm.config.profile.PwmPasswordPolicy;

import java.io.Serializable;
import java.util.Map;

@Getter
@Builder
public class UserDebugDataBean implements Serializable {
    private final UserInfoBean userInfoBean;
    private final boolean passwordReadable;
    private final Map<Permission,String> permissions;
    private final PwmPasswordPolicy ldapPasswordPolicy;
    private final PwmPasswordPolicy configuredPasswordPolicy;
    private final Map<ProfileType,String> profiles;
}
